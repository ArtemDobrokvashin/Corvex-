package ru.corvex.mailimap.server;

import org.apache.james.core.Username;
import org.apache.james.core.quota.QuotaCountUsage;
import org.apache.james.core.quota.QuotaSizeUsage;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.events.MailboxEvents;
import org.apache.james.mailbox.events.MailboxIdRegistrationKey;
import org.apache.james.mailbox.exception.AnnotationException;
import org.apache.james.mailbox.exception.BadCredentialsException;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.MailboxExistsException;
import org.apache.james.mailbox.exception.MailboxNotFoundException;
import org.apache.james.mailbox.exception.UnsupportedRightException;
import org.apache.james.mailbox.model.Mailbox;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxAnnotation;
import org.apache.james.mailbox.model.MailboxAnnotationKey;
import org.apache.james.mailbox.model.MailboxCounters;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxMetaData;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MailboxConstants;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MultimailboxesSearchQuery;
import org.apache.james.mailbox.model.QuotaRoot;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.model.UidValidity;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.model.search.MailboxQuery;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.corvex.mailimap.client.ImapAuthClient;
import ru.corvex.mailimap.client.ImapStorageClient;
import ru.corvex.mailimap.model.ImapFlags;
import ru.corvex.mailimap.model.ImapFolderPaths;

import javax.mail.Flags;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Адаптер Apache James MailboxManager поверх протокольного API mail-storage-service.
 * Хранилище остаётся единственным источником правды (mail_db + Maildir): этот класс только
 * переводит вызовы James SPI в HTTP-вызовы внутреннего API и обратно, плюс публикует события
 * ящика в EventBus James для IMAP IDLE/NOTIFY внутри этого процесса.
 */
public class CorvexMailboxManager implements MailboxManager {

    private static final Logger log = LoggerFactory.getLogger(CorvexMailboxManager.class);
    private static final String ATTR_USER_ID = "corvex.userId";
    private static final int LIST_PAGE = 1000;

    private final ImapAuthClient auth;
    private final ImapStorageClient storage;
    private final EventBus eventBus;
    private final AtomicLong sessionSequence = new AtomicLong(1);
    private final ConcurrentMap<String, ImapAuthClient.AuthenticatedUser> usersByLogin = new ConcurrentHashMap<>();

    public CorvexMailboxManager(ImapAuthClient auth, ImapStorageClient storage, EventBus eventBus) {
        this.auth = auth;
        this.storage = storage;
        this.eventBus = eventBus;
    }

    // ------------------------------------------------------------------ capabilities

    @Override
    public EnumSet<MailboxCapabilities> getSupportedMailboxCapabilities() {
        return EnumSet.of(MailboxCapabilities.Move, MailboxCapabilities.Namespace, MailboxCapabilities.Quota);
    }

    @Override
    public boolean hasCapability(MailboxCapabilities capability) {
        return getSupportedMailboxCapabilities().contains(capability);
    }

    @Override
    public EnumSet<MessageCapabilities> getSupportedMessageCapabilities() {
        return EnumSet.of(MessageCapabilities.UniqueID);
    }

    @Override
    public EnumSet<SearchCapabilities> getSupportedSearchCapabilities() {
        // Поиск выполняется по метаданным хранилища (+ лениво по сырому телу для BODY/TEXT),
        // полноценного индекса вложений нет — Attachment/FullText честно не заявляем.
        return EnumSet.of(SearchCapabilities.MultimailboxSearch, SearchCapabilities.PartialEmailMatch,
                SearchCapabilities.Text);
    }

    // ------------------------------------------------------------------ SessionProvider

    @Override
    public MailboxSession createSystemSession(Username userName) {
        MailboxSession session = newSession(userName, Optional.empty(), MailboxSession.SessionType.System, true);
        resolveActiveUser(userName).ifPresent(user -> bindUser(session, user));
        return session;
    }

    @Override
    public AuthorizationStep authenticate(Username givenUserid, String passwd) {
        return new AuthorizationStep() {
            @Override
            public MailboxSession as(Username other) throws MailboxException {
                if (other != null && other.equals(givenUserid)) {
                    return withoutDelegation();
                }
                // Делегированный доступ к чужим ящикам в Corvex пока не поддержан (нет ACL в хранилище).
                throw new BadCredentialsException();
            }

            @Override
            public MailboxSession withoutDelegation() throws MailboxException {
                ImapAuthClient.AuthenticatedUser user = authenticateAndCache(givenUserid, passwd);
                MailboxSession session = newSession(givenUserid, Optional.of(givenUserid),
                        MailboxSession.SessionType.User, false);
                bindUser(session, user);
                return session;
            }

            @Override
            public MailboxSession forMatchingUser(Predicate<Username> other) throws MailboxException {
                if (other == null || !other.test(givenUserid)) {
                    throw new BadCredentialsException();
                }
                return withoutDelegation();
            }
        };
    }

    @Override
    public AuthorizationStep authenticate(Username givenUserid) {
        // Путь без пароля James использует после внешней проверки идентичности (OIDC/pre-auth).
        // OIDC в Corvex не настроен, поэтому здесь только проверка, что пользователь существует и активен.
        return new AuthorizationStep() {
            @Override
            public MailboxSession as(Username other) throws MailboxException {
                if (other != null && other.equals(givenUserid)) {
                    return withoutDelegation();
                }
                throw new BadCredentialsException();
            }

            @Override
            public MailboxSession withoutDelegation() throws MailboxException {
                ImapAuthClient.AuthenticatedUser user = resolveActiveUserOrFail(givenUserid);
                MailboxSession session = newSession(givenUserid, Optional.of(givenUserid),
                        MailboxSession.SessionType.User, false);
                bindUser(session, user);
                return session;
            }

            @Override
            public MailboxSession forMatchingUser(Predicate<Username> other) throws MailboxException {
                if (other == null || !other.test(givenUserid)) {
                    throw new BadCredentialsException();
                }
                return withoutDelegation();
            }
        };
    }

    private MailboxSession newSession(Username user, Optional<Username> loggedIn,
                                      MailboxSession.SessionType type, boolean system) {
        long id = system ? MailboxSession.SYSTEM_SESSION_ID : sessionSequence.getAndIncrement();
        return new MailboxSession(MailboxSession.SessionId.of(id), user, loggedIn,
                List.of(), List.of(), null, ImapFolderPaths.DELIMITER, type);
    }

    private void bindUser(MailboxSession session, ImapAuthClient.AuthenticatedUser user) {
        session.getAttributes().put(ATTR_USER_ID, user.userId());
        usersByLogin.put(session.getUser().asString().toLowerCase(Locale.ROOT), user);
    }

    private ImapAuthClient.AuthenticatedUser authenticateAndCache(Username username, String password)
            throws MailboxException {
        try {
            Optional<ImapAuthClient.AuthenticatedUser> user =
                    auth.authenticate(username.asString(), password == null ? "" : password);
            if (user.isEmpty()) {
                throw new BadCredentialsException();
            }
            usersByLogin.put(username.asString().toLowerCase(Locale.ROOT), user.get());
            return user.get();
        } catch (IOException e) {
            throw new MailboxException("auth-service недоступен при IMAP-аутентификации", e);
        }
    }

    private Optional<ImapAuthClient.AuthenticatedUser> resolveActiveUser(Username username) {
        ImapAuthClient.AuthenticatedUser cached = usersByLogin.get(username.asString().toLowerCase(Locale.ROOT));
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            Optional<ImapAuthClient.AuthenticatedUser> found = auth.findActiveByUsername(username.asString());
            found.ifPresent(user -> usersByLogin.put(username.asString().toLowerCase(Locale.ROOT), user));
            return found;
        } catch (IOException e) {
            log.warn("Не удалось разрешить IMAP-пользователя {}: {}", username.asString(), e.getMessage());
            return Optional.empty();
        }
    }

    private ImapAuthClient.AuthenticatedUser resolveActiveUserOrFail(Username username) throws MailboxException {
        try {
            Optional<ImapAuthClient.AuthenticatedUser> found = auth.findActiveByUsername(username.asString());
            if (found.isEmpty()) {
                throw new BadCredentialsException();
            }
            usersByLogin.put(username.asString().toLowerCase(Locale.ROOT), found.get());
            return found.get();
        } catch (IOException e) {
            throw new MailboxException("auth-service недоступен при IMAP-аутентификации", e);
        }
    }

    UUID userId(MailboxSession session) throws MailboxException {
        Object bound = session.getAttributes().get(ATTR_USER_ID);
        if (bound instanceof UUID uuid) {
            return uuid;
        }
        ImapAuthClient.AuthenticatedUser user = resolveActiveUser(session.getUser())
                .orElseThrow(() -> new MailboxException(
                        "IMAP-пользователь не найден или неактивен в auth-service: " + session.getUser().asString()));
        bindUser(session, user);
        return user.userId();
    }

    ImapStorageClient storage() {
        return storage;
    }

    // ------------------------------------------------------------------ folder mapping helpers

    List<ImapStorageClient.FolderInfo> listFolders(MailboxSession session) throws MailboxException {
        try {
            return storage.listFolders(userId(session));
        } catch (IOException e) {
            throw wrapStorage(e);
        }
    }

    ImapStorageClient.FolderInfo folderStatus(MailboxSession session, UUID folderId) throws MailboxException {
        try {
            return storage.folderStatus(userId(session), folderId);
        } catch (IOException e) {
            throw wrapStorage(e);
        }
    }

    MailboxPath toMailboxPath(MailboxSession session, ImapStorageClient.FolderInfo folder,
                              List<ImapStorageClient.FolderInfo> folders) {
        return MailboxPath.forUser(session.getUser(), ImapFolderPaths.toImapPath(folder, folders));
    }

    Mailbox toMailbox(MailboxSession session, ImapStorageClient.FolderInfo folder,
                      List<ImapStorageClient.FolderInfo> folders) {
        Mailbox mailbox = new Mailbox(toMailboxPath(session, folder, folders),
                UidValidity.of(Math.max(1, folder.uidValidity())), CorvexMailboxId.of(folder.id()));
        mailbox.setACL(MailboxACL.OWNER_FULL_ACL);
        return mailbox;
    }

    ImapStorageClient.FolderInfo resolveFolder(MailboxSession session, MailboxPath path,
                                               List<ImapStorageClient.FolderInfo> folders)
            throws MailboxNotFoundException {
        if (path.getUser() != null && session.getUser() != null && !path.getUser().equals(session.getUser())) {
            throw new MailboxNotFoundException(path);
        }
        return ImapFolderPaths.resolve(path.getName(), folders)
                .orElseThrow(() -> new MailboxNotFoundException(path));
    }

    ImapStorageClient.FolderInfo resolveFolder(MailboxSession session, MailboxPath path) throws MailboxException {
        return resolveFolder(session, path, listFolders(session));
    }

    ImapStorageClient.FolderInfo resolveFolderById(MailboxSession session, MailboxId mailboxId,
                                                   List<ImapStorageClient.FolderInfo> folders)
            throws MailboxNotFoundException {
        UUID id = parseMailboxId(mailboxId);
        return folders.stream().filter(f -> f.id().equals(id)).findFirst()
                .orElseThrow(() -> new MailboxNotFoundException(mailboxId));
    }

    ImapStorageClient.FolderInfo resolveFolderById(MailboxSession session, MailboxId mailboxId)
            throws MailboxException {
        return resolveFolderById(session, mailboxId, listFolders(session));
    }

    static UUID parseMailboxId(MailboxId mailboxId) {
        if (mailboxId instanceof CorvexMailboxId corvex) {
            return corvex.value();
        }
        return UUID.fromString(mailboxId.serialize());
    }

    static UUID parseMessageId(MessageId messageId) {
        if (messageId instanceof CorvexMessageId corvex) {
            return corvex.value();
        }
        return UUID.fromString(messageId.serialize());
    }

    MessageMetaData toMessageMetaData(ImapStorageClient.MessageMeta meta) {
        CorvexMessageId messageId = CorvexMessageId.of(meta.id());
        return new MessageMetaData(
                MessageUid.of(meta.uid()),
                ModSeq.of(Math.max(1, meta.modseq())),
                JamesFlags.fromMeta(meta),
                meta.sizeBytes(),
                Date.from(meta.internalDate()),
                Optional.empty(),
                messageId,
                ThreadId.fromBaseMessageId(messageId));
    }

    SortedMap<MessageUid, MessageMetaData> metaDataMap(List<ImapStorageClient.MessageMeta> metas) {
        SortedMap<MessageUid, MessageMetaData> map = new TreeMap<>();
        metas.forEach(meta -> map.put(MessageUid.of(meta.uid()), toMessageMetaData(meta)));
        return map;
    }

    List<ImapStorageClient.MessageMeta> listAllMessages(MailboxSession session, UUID folderId)
            throws MailboxException {
        try {
            UUID userId = userId(session);
            List<ImapStorageClient.MessageMeta> all = new ArrayList<>();
            long afterUid = 0;
            while (true) {
                List<ImapStorageClient.MessageMeta> page = storage.listMessages(userId, folderId, afterUid, LIST_PAGE);
                all.addAll(page);
                if (page.size() < LIST_PAGE) {
                    break;
                }
                afterUid = page.get(page.size() - 1).uid();
            }
            all.sort(Comparator.comparingLong(ImapStorageClient.MessageMeta::uid));
            return all;
        } catch (IOException e) {
            throw wrapStorage(e);
        }
    }

    List<ImapStorageClient.MessageMeta> listMessagesInRange(MailboxSession session, UUID folderId, MessageRange set)
            throws MailboxException {
        return listAllMessages(session, folderId).stream()
                .filter(meta -> set.includes(MessageUid.of(meta.uid())))
                .toList();
    }

    static MailboxException wrapStorage(IOException e) {
        if (e instanceof ImapStorageClient.NotFoundException notFound) {
            return new MailboxNotFoundException(notFound.getMessage());
        }
        return new MailboxException("mail-storage-service недоступен или вернул ошибку: " + e.getMessage(), e);
    }

    // ------------------------------------------------------------------ events (IDLE/NOTIFY in-process)

    void dispatch(MailboxSession session, Event event, MailboxId mailboxId) {
        if (eventBus == null || event == null || event.isNoop()) {
            return;
        }
        try {
            Mono<Void> dispatch = eventBus.dispatch(event, new MailboxIdRegistrationKey(mailboxId));
            if (dispatch != null) {
                dispatch.subscribe();
            }
        } catch (RuntimeException e) {
            log.debug("Не удалось опубликовать IMAP-событие {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }

    void dispatchAdded(MailboxSession session, MailboxPath path, MailboxId mailboxId,
                       List<ImapStorageClient.MessageMeta> added, boolean delivery) {
        if (added.isEmpty()) {
            return;
        }
        dispatch(session, new MailboxEvents.Added(session.getSessionId(), session.getUser(), path, mailboxId,
                metaDataMap(added), Event.EventId.random(), delivery), mailboxId);
    }

    void dispatchExpunged(MailboxSession session, MailboxPath path, MailboxId mailboxId,
                          List<ImapStorageClient.MessageMeta> expunged) {
        if (expunged.isEmpty()) {
            return;
        }
        Map<MessageUid, MessageMetaData> map = new LinkedHashMap<>();
        expunged.forEach(meta -> map.put(MessageUid.of(meta.uid()), toMessageMetaData(meta)));
        dispatch(session, new MailboxEvents.Expunged(session.getSessionId(), session.getUser(), path, mailboxId,
                map, Event.EventId.random()), mailboxId);
    }

    QuotaRoot quotaRoot(MailboxSession session) {
        Username user = session.getUser();
        return QuotaRoot.quotaRoot(MailboxConstants.USER_NAMESPACE + "&" + user.asString(), user.getDomainPart());
    }

    void dispatchFlagsUpdated(MailboxSession session, MailboxPath path, MailboxId mailboxId,
                              List<UpdatedFlags> updated) {
        if (updated.isEmpty()) {
            return;
        }
        dispatch(session, new MailboxEvents.FlagsUpdated(session.getSessionId(), session.getUser(), path, mailboxId,
                updated, Event.EventId.random()), mailboxId);
    }

    // ------------------------------------------------------------------ MailboxManager: mailboxes

    @Override
    public MessageManager getMailbox(MailboxPath mailboxPath, MailboxSession session) throws MailboxException {
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        ImapStorageClient.FolderInfo folder = resolveFolder(session, mailboxPath, folders);
        return new CorvexMessageManager(this, folder, toMailboxPath(session, folder, folders));
    }

    @Override
    public MessageManager getMailbox(MailboxId mailboxId, MailboxSession session) throws MailboxException {
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        ImapStorageClient.FolderInfo folder = resolveFolderById(session, mailboxId, folders);
        return new CorvexMessageManager(this, folder, toMailboxPath(session, folder, folders));
    }

    @Override
    public Publisher<MessageManager> getMailboxReactive(MailboxId mailboxId, MailboxSession session) {
        return Mono.fromCallable(() -> getMailbox(mailboxId, session));
    }

    @Override
    public Publisher<MessageManager> getMailboxReactive(MailboxPath mailboxPath, MailboxSession session) {
        return Mono.fromCallable(() -> getMailbox(mailboxPath, session));
    }

    @Override
    public MessageManager getMailbox(Mailbox mailbox, MailboxSession session) throws MailboxException {
        return getMailbox(mailbox.getMailboxId(), session);
    }

    @Override
    public Optional<MailboxId> createMailbox(MailboxPath mailboxPath, CreateOption createOption,
                                             MailboxSession mailboxSession) throws MailboxException {
        List<String> segments = ImapFolderPaths.segments(mailboxPath.getName());
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        List<ImapStorageClient.FolderInfo> folders = listFolders(mailboxSession);
        if (ImapFolderPaths.resolve(mailboxPath.getName(), folders).isPresent()) {
            throw new MailboxExistsException(mailboxPath.getName());
        }
        UUID parentId = null;
        ImapStorageClient.FolderInfo created = null;
        StringBuilder currentPath = new StringBuilder();
        for (String segment : segments) {
            if (currentPath.length() > 0) {
                currentPath.append(ImapFolderPaths.DELIMITER);
            }
            currentPath.append(segment);
            Optional<ImapStorageClient.FolderInfo> existing =
                    ImapFolderPaths.resolve(currentPath.toString(), folders);
            if (existing.isPresent()) {
                created = existing.get();
                parentId = created.id();
                continue;
            }
            try {
                created = storage.createFolder(userId(mailboxSession), segment, parentId);
            } catch (IOException e) {
                throw wrapStorage(e);
            }
            folders = new ArrayList<>(folders);
            folders.add(created);
            parentId = created.id();
        }
        MailboxId mailboxId = CorvexMailboxId.of(created.id());
        dispatch(mailboxSession, new MailboxEvents.MailboxAdded(mailboxSession.getSessionId(), mailboxSession.getUser(),
                toMailboxPath(mailboxSession, created, folders), mailboxId, Event.EventId.random()), mailboxId);
        return Optional.of(mailboxId);
    }

    @Override
    public void deleteMailbox(MailboxPath mailboxPath, MailboxSession session) throws MailboxException {
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        ImapStorageClient.FolderInfo folder = resolveFolder(session, mailboxPath, folders);
        deleteFolder(session, folder, toMailboxPath(session, folder, folders));
    }

    @Override
    public Mailbox deleteMailbox(MailboxId mailboxId, MailboxSession session) throws MailboxException {
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        ImapStorageClient.FolderInfo folder = resolveFolderById(session, mailboxId, folders);
        Mailbox mailbox = toMailbox(session, folder, folders);
        deleteFolder(session, folder, mailbox.generateAssociatedPath());
        return mailbox;
    }

    private void deleteFolder(MailboxSession session, ImapStorageClient.FolderInfo folder, MailboxPath path)
            throws MailboxException {
        try {
            storage.deleteFolder(userId(session), folder.id());
        } catch (IOException e) {
            throw wrapStorage(e);
        }
        MailboxId mailboxId = CorvexMailboxId.of(folder.id());
        dispatch(session, new MailboxEvents.MailboxDeletion(session.getSessionId(), session.getUser(), path,
                MailboxACL.OWNER_FULL_ACL, quotaRoot(session), QuotaCountUsage.count(folder.messageCount()),
                QuotaSizeUsage.size(0), mailboxId, Event.EventId.random()), mailboxId);
    }

    @Override
    public List<MailboxRenamedResult> renameMailbox(MailboxPath from, MailboxPath to, RenameOption option,
                                                    MailboxSession session) throws MailboxException {
        List<ImapStorageClient.FolderInfo> before = listFolders(session);
        ImapStorageClient.FolderInfo folder = resolveFolder(session, from, before);
        if (ImapFolderPaths.resolve(to.getName(), before).isPresent()) {
            throw new MailboxExistsException(to.getName());
        }
        Map<UUID, MailboxPath> oldPaths = new LinkedHashMap<>();
        collectSelfAndDescendants(session, folder, before, oldPaths);

        List<String> segments = ImapFolderPaths.segments(to.getName());
        if (segments.isEmpty()) {
            throw new MailboxException("Пустое новое имя папки");
        }
        String leaf = segments.get(segments.size() - 1);
        UUID targetParentId = null;
        boolean moveToRoot = segments.size() == 1;
        if (!moveToRoot) {
            String parentPath = String.join(String.valueOf(ImapFolderPaths.DELIMITER),
                    segments.subList(0, segments.size() - 1));
            Optional<ImapStorageClient.FolderInfo> parent = ImapFolderPaths.resolve(parentPath, before);
            if (parent.isEmpty()) {
                Optional<MailboxId> createdParent = createMailbox(MailboxPath.forUser(session.getUser(), parentPath),
                        CreateOption.NONE, session);
                targetParentId = createdParent.map(CorvexMailboxManager::parseMailboxId).orElse(null);
                before = listFolders(session);
            } else {
                targetParentId = parent.get().id();
            }
        }
        try {
            storage.renameFolder(userId(session), folder.id(), leaf, targetParentId, moveToRoot);
        } catch (IOException e) {
            throw wrapStorage(e);
        }
        List<ImapStorageClient.FolderInfo> after = listFolders(session);
        List<MailboxRenamedResult> results = new ArrayList<>();
        for (Map.Entry<UUID, MailboxPath> entry : oldPaths.entrySet()) {
            after.stream().filter(f -> f.id().equals(entry.getKey())).findFirst()
                    .ifPresent(renamed -> results.add(new MailboxRenamedResult(
                            CorvexMailboxId.of(renamed.id()), entry.getValue(),
                            toMailboxPath(session, renamed, after))));
        }
        results.stream().findFirst().ifPresent(result -> dispatch(session,
                new MailboxEvents.MailboxRenamed(session.getSessionId(), session.getUser(), result.getOriginPath(),
                        result.getMailboxId(), result.getDestinationPath(), Event.EventId.random()),
                result.getMailboxId()));
        return results;
    }

    private void collectSelfAndDescendants(MailboxSession session, ImapStorageClient.FolderInfo folder,
                                           List<ImapStorageClient.FolderInfo> folders,
                                           Map<UUID, MailboxPath> out) {
        out.put(folder.id(), toMailboxPath(session, folder, folders));
        folders.stream().filter(f -> folder.id().equals(f.parentId()))
                .forEach(child -> collectSelfAndDescendants(session, child, folders, out));
    }

    @Override
    public List<MailboxRenamedResult> renameMailbox(MailboxId mailboxId, MailboxPath newMailboxPath,
                                                    RenameOption option, MailboxSession session)
            throws MailboxException {
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        ImapStorageClient.FolderInfo folder = resolveFolderById(session, mailboxId, folders);
        return renameMailbox(toMailboxPath(session, folder, folders), newMailboxPath, option, session);
    }

    @Override
    public List<MessageRange> copyMessages(MessageRange set, MailboxPath from, MailboxPath to, MailboxSession session)
            throws MailboxException {
        return copyOrMove(set, resolveFolder(session, from), resolveFolder(session, to), session, false);
    }

    @Override
    public List<MessageRange> copyMessages(MessageRange set, MailboxId from, MailboxId to, MailboxSession session)
            throws MailboxException {
        return copyOrMove(set, resolveFolderById(session, from), resolveFolderById(session, to), session, false);
    }

    @Override
    public List<MessageRange> moveMessages(MessageRange set, MailboxPath from, MailboxPath to, MailboxSession session)
            throws MailboxException {
        return copyOrMove(set, resolveFolder(session, from), resolveFolder(session, to), session, true);
    }

    @Override
    public List<MessageRange> moveMessages(MessageRange set, MailboxId from, MailboxId to, MailboxSession session)
            throws MailboxException {
        return copyOrMove(set, resolveFolderById(session, from), resolveFolderById(session, to), session, true);
    }

    private List<MessageRange> copyOrMove(MessageRange set, ImapStorageClient.FolderInfo from,
                                          ImapStorageClient.FolderInfo to, MailboxSession session, boolean move)
            throws MailboxException {
        List<ImapStorageClient.MessageMeta> sourceMetas = listMessagesInRange(session, from.id(), set);
        if (sourceMetas.isEmpty()) {
            return List.of();
        }
        List<Long> sourceUids = sourceMetas.stream().map(ImapStorageClient.MessageMeta::uid).toList();
        ImapStorageClient.CopyResponse response;
        try {
            response = storage.copyMessages(userId(session), from.id(), sourceUids, to.id(), move);
        } catch (IOException e) {
            throw wrapStorage(e);
        }
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        MailboxPath toPath = toMailboxPath(session, to, folders);
        List<ImapStorageClient.MessageMeta> added = listAllMessages(session, to.id()).stream()
                .filter(meta -> response.targetUids().contains(meta.uid()))
                .toList();
        dispatchAdded(session, toPath, CorvexMailboxId.of(to.id()), added, false);
        if (move) {
            dispatchExpunged(session, toMailboxPath(session, from, folders), CorvexMailboxId.of(from.id()), sourceMetas);
        }
        return MessageRange.toRanges(response.targetUids().stream().map(MessageUid::of).toList());
    }

    @Override
    public Flux<MailboxMetaData> search(MailboxQuery expression, MailboxSearchFetchType fetchType,
                                        MailboxSession session) {
        return Flux.defer(() -> {
            try {
                List<ImapStorageClient.FolderInfo> folders = listFolders(session);
                List<MailboxMetaData> results = new ArrayList<>();
                for (ImapStorageClient.FolderInfo folder : folders) {
                    Mailbox mailbox = toMailbox(session, folder, folders);
                    if (!expression.matches(mailbox)) {
                        continue;
                    }
                    boolean hasChildren = folders.stream().anyMatch(f -> folder.id().equals(f.parentId()));
                    MailboxCounters counters = fetchType == MailboxSearchFetchType.Counters
                            ? MailboxCounters.builder().mailboxId(CorvexMailboxId.of(folder.id()))
                            .count(folder.messageCount()).unseen(folder.unseenCount()).build()
                            : MailboxCounters.empty(CorvexMailboxId.of(folder.id()));
                    results.add(new MailboxMetaData(mailbox, ImapFolderPaths.DELIMITER,
                            hasChildren ? MailboxMetaData.Children.HAS_CHILDREN : MailboxMetaData.Children.HAS_NO_CHILDREN,
                            MailboxMetaData.Selectability.NONE, MailboxACL.OWNER_FULL_ACL, counters));
                }
                results.sort(MailboxMetaData.COMPARATOR);
                return Flux.fromIterable(results);
            } catch (MailboxException e) {
                return Flux.error(e);
            }
        });
    }

    @Override
    public Publisher<MessageId> search(MultimailboxesSearchQuery expression, MailboxSession session, long limit) {
        return Flux.defer(() -> {
            try {
                List<ImapStorageClient.FolderInfo> folders = listFolders(session);
                Set<UUID> in = expression.getInMailboxes().stream()
                        .map(CorvexMailboxManager::parseMailboxId).collect(java.util.stream.Collectors.toSet());
                Set<UUID> notIn = expression.getNotInMailboxes().stream()
                        .map(CorvexMailboxManager::parseMailboxId).collect(java.util.stream.Collectors.toSet());
                List<MessageId> results = new ArrayList<>();
                for (ImapStorageClient.FolderInfo folder : folders) {
                    if ((!in.isEmpty() && !in.contains(folder.id())) || notIn.contains(folder.id())) {
                        continue;
                    }
                    List<ImapStorageClient.MessageMeta> metas = listAllMessages(session, folder.id());
                    for (ImapStorageClient.MessageMeta meta : metas) {
                        if (CorvexSearch.matches(expression.getSearchQuery(), meta,
                                m -> readRaw(session, folder.id(), m))) {
                            results.add(CorvexMessageId.of(meta.id()));
                            if (limit > 0 && results.size() >= limit) {
                                return Flux.fromIterable(results);
                            }
                        }
                    }
                }
                return Flux.fromIterable(results);
            } catch (MailboxException e) {
                return Flux.error(e);
            }
        });
    }

    byte[] readRaw(MailboxSession session, UUID folderId, ImapStorageClient.MessageMeta meta) throws MailboxException {
        try {
            return storage.readRawByUid(userId(session), folderId, meta.uid());
        } catch (IOException e) {
            throw wrapStorage(e);
        }
    }

    @Override
    public Publisher<MessageId> getThread(ThreadId threadId, MailboxSession session) {
        // Тре threading в хранилище пока нет: ThreadId = base MessageId, поэтому тред состоит из одного письма.
        return Mono.just(threadId.getBaseMessageId());
    }

    @Override
    public Publisher<Boolean> mailboxExists(MailboxPath mailboxPath, MailboxSession session) {
        return Mono.fromCallable(() -> {
            try {
                resolveFolder(session, mailboxPath);
                return true;
            } catch (MailboxNotFoundException e) {
                return false;
            }
        });
    }

    @Override
    public List<MailboxPath> list(MailboxSession session) throws MailboxException {
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        return folders.stream().map(folder -> toMailboxPath(session, folder, folders)).toList();
    }

    @Override
    public boolean hasChildren(MailboxPath mailboxPath, MailboxSession session) throws MailboxException {
        List<ImapStorageClient.FolderInfo> folders = listFolders(session);
        ImapStorageClient.FolderInfo folder = resolveFolder(session, mailboxPath, folders);
        return folders.stream().anyMatch(f -> folder.id().equals(f.parentId()));
    }

    @Override
    public Publisher<Boolean> hasChildrenReactive(MailboxPath mailboxPath, MailboxSession session) {
        return Mono.fromCallable(() -> hasChildren(mailboxPath, session));
    }

    @Override
    public MessageId.Factory getMessageIdFactory() {
        return new CorvexMessageId.Factory();
    }

    // ------------------------------------------------------------------ RequestAware

    @Override
    public void startProcessingRequest(MailboxSession session) {
        // Stateless HTTP-адаптер: транзакций на сессию не держит.
    }

    @Override
    public void endProcessingRequest(MailboxSession session) {
        // Stateless HTTP-адаптер: транзакций на сессию не держит.
    }

    // ------------------------------------------------------------------ RightManager (owner-only, ACL не персистится)

    @Override
    public boolean hasRight(MailboxPath mailboxPath, MailboxACL.Right right, MailboxSession session)
            throws MailboxException {
        resolveFolder(session, mailboxPath);
        return true;
    }

    @Override
    public Publisher<Boolean> hasRightReactive(MailboxPath mailboxPath, MailboxACL.Right right, MailboxSession session) {
        return Mono.fromCallable(() -> hasRight(mailboxPath, right, session));
    }

    @Override
    public boolean hasRight(Mailbox mailbox, MailboxACL.Right right, MailboxSession session) throws MailboxException {
        resolveFolderById(session, mailbox.getMailboxId());
        return true;
    }

    @Override
    public boolean hasRight(MailboxId mailboxId, MailboxACL.Right right, MailboxSession session)
            throws MailboxException {
        resolveFolderById(session, mailboxId);
        return true;
    }

    @Override
    public List<MailboxACL.Rfc4314Rights> listRights(MailboxPath mailboxPath, MailboxACL.EntryKey identifier,
                                                     MailboxSession session) throws MailboxException {
        resolveFolder(session, mailboxPath);
        return List.of(MailboxACL.FULL_RIGHTS);
    }

    @Override
    public List<MailboxACL.Rfc4314Rights> listRights(Mailbox mailbox, MailboxACL.EntryKey identifier,
                                                     MailboxSession session) throws MailboxException {
        resolveFolderById(session, mailbox.getMailboxId());
        return List.of(MailboxACL.FULL_RIGHTS);
    }

    @Override
    public MailboxACL listRights(MailboxPath mailboxPath, MailboxSession session) throws MailboxException {
        resolveFolder(session, mailboxPath);
        return MailboxACL.OWNER_FULL_ACL;
    }

    @Override
    public MailboxACL listRights(MailboxId mailboxId, MailboxSession session) throws MailboxException {
        resolveFolderById(session, mailboxId);
        return MailboxACL.OWNER_FULL_ACL;
    }

    @Override
    public MailboxACL.Rfc4314Rights myRights(MailboxPath mailboxPath, MailboxSession session) throws MailboxException {
        resolveFolder(session, mailboxPath);
        return MailboxACL.FULL_RIGHTS;
    }

    @Override
    public Publisher<MailboxACL.Rfc4314Rights> myRightsReactive(MailboxPath mailboxPath, MailboxSession session) {
        return Mono.fromCallable(() -> myRights(mailboxPath, session));
    }

    @Override
    public Publisher<MailboxACL.Rfc4314Rights> myRights(MailboxId mailboxId, MailboxSession session) {
        return Mono.fromCallable(() -> {
            resolveFolderById(session, mailboxId);
            return MailboxACL.FULL_RIGHTS;
        });
    }

    @Override
    public MailboxACL.Rfc4314Rights myRights(Mailbox mailbox, MailboxSession session) {
        return MailboxACL.FULL_RIGHTS;
    }

    @Override
    public void applyRightsCommand(MailboxPath mailboxPath, MailboxACL.ACLCommand mailboxACLCommand,
                                   MailboxSession session) throws MailboxException {
        throw new UnsupportedRightException();
    }

    @Override
    public Publisher<Void> applyRightsCommandReactive(MailboxPath mailboxPath, MailboxACL.ACLCommand mailboxACLCommand,
                                                      MailboxSession session) {
        return Mono.error(new UnsupportedRightException());
    }

    @Override
    public void applyRightsCommand(MailboxId mailboxId, MailboxACL.ACLCommand mailboxACLCommand,
                                   MailboxSession session) throws MailboxException {
        throw new UnsupportedRightException();
    }

    @Override
    public void setRights(MailboxPath mailboxPath, MailboxACL mailboxACL, MailboxSession session)
            throws MailboxException {
        throw new UnsupportedRightException();
    }

    @Override
    public void setRights(MailboxId mailboxId, MailboxACL mailboxACL, MailboxSession session)
            throws MailboxException {
        throw new UnsupportedRightException();
    }

    // ------------------------------------------------------------------ MailboxAnnotationManager (не персистится)

    @Override
    public List<MailboxAnnotation> getAllAnnotations(MailboxPath mailboxPath, MailboxSession session) {
        return List.of();
    }

    @Override
    public Publisher<MailboxAnnotation> getAllAnnotationsReactive(MailboxPath mailboxPath, MailboxSession session) {
        return Flux.empty();
    }

    @Override
    public List<MailboxAnnotation> getAnnotationsByKeys(MailboxPath mailboxPath, MailboxSession session,
                                                        Set<MailboxAnnotationKey> keys) {
        return List.of();
    }

    @Override
    public Publisher<MailboxAnnotation> getAnnotationsByKeysReactive(MailboxPath mailboxPath, MailboxSession session,
                                                                      Set<MailboxAnnotationKey> keys) {
        return Flux.empty();
    }

    @Override
    public List<MailboxAnnotation> getAnnotationsByKeysWithOneDepth(MailboxPath mailboxPath, MailboxSession session,
                                                                    Set<MailboxAnnotationKey> keys) {
        return List.of();
    }

    @Override
    public Publisher<MailboxAnnotation> getAnnotationsByKeysWithOneDepthReactive(MailboxPath mailboxPath,
                                                                                  MailboxSession session,
                                                                                  Set<MailboxAnnotationKey> keys) {
        return Flux.empty();
    }

    @Override
    public List<MailboxAnnotation> getAnnotationsByKeysWithAllDepth(MailboxPath mailboxPath, MailboxSession session,
                                                                    Set<MailboxAnnotationKey> keys) {
        return List.of();
    }

    @Override
    public Publisher<MailboxAnnotation> getAnnotationsByKeysWithAllDepthReactive(MailboxPath mailboxPath,
                                                                                  MailboxSession session,
                                                                                  Set<MailboxAnnotationKey> keys) {
        return Flux.empty();
    }

    @Override
    public void updateAnnotations(MailboxPath mailboxPath, MailboxSession session,
                                  List<MailboxAnnotation> mailboxAnnotations) throws AnnotationException {
        throw new AnnotationException("Аннотации папок (METADATA) пока не поддерживаются Corvex");
    }

    @Override
    public Publisher<Void> updateAnnotationsReactive(MailboxPath mailboxPath, MailboxSession session,
                                                     List<MailboxAnnotation> mailboxAnnotations) {
        return Mono.error(new AnnotationException("Аннотации папок (METADATA) пока не поддерживаются Corvex"));
    }
}
