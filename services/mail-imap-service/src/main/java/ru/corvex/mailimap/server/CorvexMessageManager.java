package ru.corvex.mailimap.server;

import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.UnsupportedRightException;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.ComposedMessageIdWithMetaData;
import org.apache.james.mailbox.model.FetchGroup;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxCounters;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResultIterator;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.corvex.mailimap.client.ImapStorageClient;
import ru.corvex.mailimap.model.ImapFlags;

import javax.mail.Flags;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * James MessageManager одной папки поверх протокольного API хранилища. Все операции —
 * синхронные HTTP-вызовы (James допускает blocking SPI; reactive-методы оборачивают их в Mono).
 */
final class CorvexMessageManager implements MessageManager {

    private final CorvexMailboxManager manager;
    private final UUID folderId;
    private final MailboxId mailboxId;
    private final MailboxPath mailboxPath;
    private final long uidValidity;

    CorvexMessageManager(CorvexMailboxManager manager, ImapStorageClient.FolderInfo folder, MailboxPath mailboxPath) {
        this.manager = manager;
        this.folderId = folder.id();
        this.mailboxId = CorvexMailboxId.of(folder.id());
        this.mailboxPath = mailboxPath;
        this.uidValidity = Math.max(1, folder.uidValidity());
    }

    @Override
    public long getMessageCount(MailboxSession mailboxSession) throws MailboxException {
        return manager.folderStatus(mailboxSession, folderId).messageCount();
    }

    @Override
    public MailboxCounters getMailboxCounters(MailboxSession mailboxSession) throws MailboxException {
        ImapStorageClient.FolderInfo status = manager.folderStatus(mailboxSession, folderId);
        return MailboxCounters.builder().mailboxId(mailboxId)
                .count(status.messageCount()).unseen(status.unseenCount()).build();
    }

    @Override
    public Publisher<MailboxCounters> getMailboxCountersReactive(MailboxSession mailboxSession) {
        return Mono.fromCallable(() -> getMailboxCounters(mailboxSession));
    }

    @Override
    @Deprecated
    public boolean isWriteable(MailboxSession session) {
        return true;
    }

    @Override
    public Publisher<MessageUid> search(SearchQuery searchQuery, MailboxSession mailboxSession)
            throws MailboxException {
        List<MessageUid> results = new ArrayList<>();
        for (ImapStorageClient.MessageMeta meta : manager.listAllMessages(mailboxSession, folderId)) {
            if (CorvexSearch.matches(searchQuery, meta, m -> manager.readRaw(mailboxSession, folderId, m))) {
                results.add(MessageUid.of(meta.uid()));
            }
        }
        return Flux.fromIterable(results);
    }

    @Override
    public Iterator<MessageUid> expunge(MessageRange set, MailboxSession mailboxSession) throws MailboxException {
        List<ImapStorageClient.MessageMeta> victims = manager.listMessagesInRange(mailboxSession, folderId, set).stream()
                .filter(ImapStorageClient.MessageMeta::deleted)
                .toList();
        if (victims.isEmpty()) {
            return List.<MessageUid>of().iterator();
        }
        List<Long> expunged;
        try {
            expunged = manager.storage().expunge(manager.userId(mailboxSession), folderId,
                    victims.stream().map(ImapStorageClient.MessageMeta::uid).toList());
        } catch (IOException e) {
            throw CorvexMailboxManager.wrapStorage(e);
        }
        List<ImapStorageClient.MessageMeta> actuallyExpunged = victims.stream()
                .filter(meta -> expunged.contains(meta.uid()))
                .toList();
        manager.dispatchExpunged(mailboxSession, mailboxPath, mailboxId, actuallyExpunged);
        return expunged.stream().map(MessageUid::of).iterator();
    }

    @Override
    public void delete(List<MessageUid> uids, MailboxSession mailboxSession) throws MailboxException {
        if (uids == null || uids.isEmpty()) {
            return;
        }
        // James delete = физическое удаление. В терминах хранилища: \Deleted + UID EXPUNGE.
        try {
            UUID userId = manager.userId(mailboxSession);
            for (MessageUid uid : uids) {
                manager.storage().updateFlags(userId, folderId, uid.asLong(),
                        new ImapStorageClient.FlagsUpdate(null, null, null, null, Boolean.TRUE));
            }
            manager.storage().expunge(userId, folderId, uids.stream().map(MessageUid::asLong).toList());
        } catch (IOException e) {
            throw CorvexMailboxManager.wrapStorage(e);
        }
    }

    @Override
    public Map<MessageUid, Flags> setFlags(Flags flags, FlagsUpdateMode flagsUpdateMode, MessageRange set,
                                           MailboxSession mailboxSession) throws MailboxException {
        Map<MessageUid, Flags> results = new LinkedHashMap<>();
        List<UpdatedFlags> updatedFlags = new ArrayList<>();
        Set<String> requested = JamesFlags.toStrings(flags);
        try {
            UUID userId = manager.userId(mailboxSession);
            for (ImapStorageClient.MessageMeta meta : manager.listMessagesInRange(mailboxSession, folderId, set)) {
                Set<String> before = ImapFlags.fromMeta(meta);
                Set<String> after = new java.util.LinkedHashSet<>(before);
                switch (flagsUpdateMode) {
                    case ADD -> after.addAll(requested);
                    case REMOVE -> after.removeAll(requested);
                    case REPLACE -> {
                        after.clear();
                        after.addAll(requested);
                    }
                }
                after.remove("\\Recent"); // сессионный флаг Джеймса, в хранилище не персистится
                Flags newFlags = JamesFlags.fromStrings(after);
                results.put(MessageUid.of(meta.uid()), newFlags);
                if (!before.equals(after)) {
                    ImapStorageClient.MessageMeta updated = manager.storage().updateFlags(
                            userId, folderId, meta.uid(), JamesFlags.toUpdate(after));
                    updatedFlags.add(UpdatedFlags.builder()
                            .uid(MessageUid.of(meta.uid()))
                            .messageId(CorvexMessageId.of(meta.id()))
                            .modSeq(ModSeq.of(Math.max(1, updated.modseq())))
                            .oldFlags(JamesFlags.fromStrings(before))
                            .newFlags(newFlags)
                            .build());
                }
            }
        } catch (IOException e) {
            throw CorvexMailboxManager.wrapStorage(e);
        }
        manager.dispatchFlagsUpdated(mailboxSession, mailboxPath, mailboxId, updatedFlags);
        return results;
    }

    @Override
    public AppendResult appendMessage(InputStream msgIn, Date internalDate, MailboxSession mailboxSession,
                                      boolean isRecent, Flags flags) throws MailboxException {
        try {
            return appendBytes(msgIn.readAllBytes(), internalDate, flags, mailboxSession, false);
        } catch (IOException e) {
            throw new MailboxException("Не удалось прочитать APPENd-сообщение", e);
        }
    }

    @Override
    public AppendResult appendMessage(AppendCommand appendCommand, MailboxSession session) throws MailboxException {
        try (InputStream in = appendCommand.getMsgIn().getInputStream()) {
            return appendBytes(in.readAllBytes(), appendCommand.getInternalDate(), appendCommand.getFlags(),
                    session, appendCommand.isDelivery());
        } catch (IOException e) {
            throw new MailboxException("Не удалось прочитать APPENd-сообщение", e);
        }
    }

    @Override
    public Publisher<AppendResult> appendMessageReactive(AppendCommand appendCommand, MailboxSession session) {
        return Mono.fromCallable(() -> appendMessage(appendCommand, session));
    }

    private AppendResult appendBytes(byte[] raw, Date internalDate, Flags flags, MailboxSession session,
                                     boolean delivery) throws MailboxException {
        ImapStorageClient.AppendResponse response;
        try {
            response = manager.storage().append(manager.userId(session), folderId, raw,
                    internalDate == null ? Instant.now() : internalDate.toInstant(), JamesFlags.toHeader(flags));
        } catch (IOException e) {
            throw CorvexMailboxManager.wrapStorage(e);
        }
        CorvexMessageId messageId = CorvexMessageId.of(response.messageId());
        ComposedMessageId composed = new ComposedMessageId(mailboxId, messageId, MessageUid.of(response.uid()));
        // Событие Added для IDLE: метаданные собираем из ответа append и запрошенных флагов,
        // чтобы не делать лишний round-trip в хранилище.
        ImapStorageClient.MessageMeta meta = new ImapStorageClient.MessageMeta(
                response.messageId(), response.uid(), response.modseq(), null, "", "",
                internalDate == null ? Instant.now() : internalDate.toInstant(), raw.length,
                flags != null && flags.contains(Flags.Flag.SEEN),
                flags != null && flags.contains(Flags.Flag.ANSWERED),
                flags != null && flags.contains(Flags.Flag.FLAGGED),
                flags != null && flags.contains(Flags.Flag.DRAFT),
                flags != null && flags.contains(Flags.Flag.DELETED));
        manager.dispatchAdded(session, mailboxPath, mailboxId, List.of(meta), delivery);
        return new AppendResult(composed, (long) raw.length, Optional.empty(), ThreadId.fromBaseMessageId(messageId));
    }

    @Override
    public MessageResultIterator getMessages(MessageRange set, FetchGroup fetchGroup, MailboxSession mailboxSession)
            throws MailboxException {
        List<org.apache.james.mailbox.model.MessageResult> results = new ArrayList<>();
        for (ImapStorageClient.MessageMeta meta : manager.listMessagesInRange(mailboxSession, folderId, set)) {
            results.add(new CorvexMessageResult(manager, mailboxSession, mailboxId, folderId, meta));
        }
        Iterator<org.apache.james.mailbox.model.MessageResult> delegate = results.iterator();
        return new MessageResultIterator() {
            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public org.apache.james.mailbox.model.MessageResult next() {
                return delegate.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            @Override
            public MailboxException getException() {
                return null;
            }
        };
    }

    @Override
    public Publisher<ComposedMessageIdWithMetaData> listMessagesMetadata(MessageRange set, MailboxSession session) {
        return Flux.defer(() -> {
            try {
                List<ComposedMessageIdWithMetaData> results = new ArrayList<>();
                for (ImapStorageClient.MessageMeta meta : manager.listMessagesInRange(session, folderId, set)) {
                    CorvexMessageId messageId = CorvexMessageId.of(meta.id());
                    results.add(new ComposedMessageIdWithMetaData(
                            new ComposedMessageId(mailboxId, messageId, MessageUid.of(meta.uid())),
                            JamesFlags.fromMeta(meta),
                            ModSeq.of(Math.max(1, meta.modseq())),
                            ThreadId.fromBaseMessageId(messageId)));
                }
                return Flux.fromIterable(results);
            } catch (MailboxException e) {
                return Flux.error(e);
            }
        });
    }

    @Override
    public Mailbox getMailboxEntity() throws MailboxException {
        // James зовёт этот метод без сессии, поэтому UIDVALIDITY берём из снапшота папки на момент открытия.
        Mailbox mailbox = new Mailbox(mailboxPath, UidValidity.of(uidValidity), mailboxId);
        mailbox.setACL(MailboxACL.OWNER_FULL_ACL);
        return mailbox;
    }

    @Override
    public EnumSet<MailboxManager.MessageCapabilities> getSupportedMessageCapabilities() {
        return EnumSet.of(MailboxManager.MessageCapabilities.UniqueID);
    }

    @Override
    public MailboxId getId() {
        return mailboxId;
    }

    @Override
    public MailboxPath getMailboxPath() {
        return mailboxPath;
    }

    @Override
    public Flags getApplicableFlags(MailboxSession session) {
        return JamesFlags.applicableFlags();
    }

    @Override
    public MessageManager.MailboxMetaData getMetaData(RecentMode recentMode, MailboxSession mailboxSession,
                                                      EnumSet<MessageManager.MailboxMetaData.Item> items)
            throws MailboxException {
        ImapStorageClient.FolderInfo status = manager.folderStatus(mailboxSession, folderId);
        MessageUid firstUnseen = null;
        if (items == null || items.contains(MessageManager.MailboxMetaData.Item.FirstUnseen)) {
            firstUnseen = manager.listAllMessages(mailboxSession, folderId).stream()
                    .filter(meta -> !meta.seen())
                    .findFirst()
                    .map(meta -> MessageUid.of(meta.uid()))
                    .orElse(null);
        }
        return new MessageManager.MailboxMetaData(
                List.of(), // \Recent хранилище не трекает по сессиям; клиенты ориентируются на UNSEEN
                JamesFlags.permanentFlags(),
                UidValidity.of(Math.max(1, status.uidValidity())),
                MessageUid.of(Math.max(1, status.uidNext())),
                ModSeq.of(Math.max(1, status.highestModseq())),
                status.messageCount(),
                status.unseenCount(),
                firstUnseen,
                true,
                MailboxACL.OWNER_FULL_ACL);
    }

    @Override
    public Flags getPermanentFlags(MailboxSession session) {
        return JamesFlags.permanentFlags();
    }

    @Override
    public MailboxACL getResolvedAcl(MailboxSession mailboxSession) throws UnsupportedRightException {
        return MailboxACL.OWNER_FULL_ACL;
    }
}
