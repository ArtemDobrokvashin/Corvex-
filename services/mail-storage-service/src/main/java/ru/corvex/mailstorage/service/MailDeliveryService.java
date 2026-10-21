package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailImportedMessage;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.pipeline.MailPipelineEvents;
import ru.corvex.mailstorage.repository.ExternalMailImportedMessageRepository;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Финальное звено конвейера: один вызов = один получатель = одна транзакция.
 * Так повтор события после частичного сбоя безопасен: уже доставленные получатели отсекаются
 * по (raw_ref, owner_id), остальные доставляются при retry.
 */
@Service
public class MailDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(MailDeliveryService.class);

    private final RawMessageSpool spool;
    private final MimeMailParser parser;
    private final MaildirStorage maildir;
    private final MailFolderRepository folders;
    private final MessageMetadataRepository messages;
    private final ExternalMailImportedMessageRepository externalImported;
    private final AuthUserClient auth;
    private final JdbcTemplate jdbc;

    public MailDeliveryService(RawMessageSpool spool,
                               MimeMailParser parser,
                               MaildirStorage maildir,
                               MailFolderRepository folders,
                               MessageMetadataRepository messages,
                               ExternalMailImportedMessageRepository externalImported,
                               AuthUserClient auth,
                               JdbcTemplate jdbc) {
        this.spool = spool;
        this.parser = parser;
        this.maildir = maildir;
        this.folders = folders;
        this.messages = messages;
        this.externalImported = externalImported;
        this.auth = auth;
        this.jdbc = jdbc;
    }

    public record DeliveryResult(UUID messageId, UUID ownerId, UUID folderId, long uid,
                                 String mailbox, MimeMailParser.ParsedMail parsed) {
    }

    @Transactional
    public Optional<DeliveryResult> deliver(MailPipelineEvents.AvCheckedEvent event, String recipientRaw) throws IOException {
        String recipient = normalizeAddress(recipientRaw);
        Optional<AuthUserClient.MailboxOwner> ownerOpt = auth.findByEmail(recipient);
        if (ownerOpt.isEmpty() || !ownerOpt.get().active()) {
            log.warn("Пропущена доставка rawRef={}: получатель {} не найден или неактивен", event.rawRef(), recipient);
            return Optional.empty();
        }
        AuthUserClient.MailboxOwner owner = ownerOpt.get();
        ExternalMailImportedMessage external = externalImported.findFirstByRawRef(event.rawRef())
                .filter(record -> record.getAccount() != null && owner.id().equals(record.getAccount().getOwnerId()))
                .orElse(null);
        if (messages.existsByRawRefAndOwnerId(event.rawRef(), owner.id())) {
            if (external != null && external.getStatus() == ExternalMailImportedMessage.Status.QUEUED) {
                external.setStatus(ExternalMailImportedMessage.Status.DELIVERED);
                externalImported.save(external);
            }
            return Optional.empty();
        }

        long used = messages.sumSizeByOwnerId(owner.id());
        if (owner.quotaBytes() > 0 && used + event.sizeBytes() > owner.quotaBytes()) {
            // DSN отправителю появится с исходящим воркером этапа 2B; сейчас фиксируем отказ в логе/метриках,
            // но не кладём письмо сверх квоты и не блокируем остальных получателей этого же письма.
            log.warn("Квота превышена для {}: used={} incoming={} quota={} rawRef={}",
                    recipient, used, event.sizeBytes(), owner.quotaBytes(), event.rawRef());
            if (external != null) {
                external.setStatus(ExternalMailImportedMessage.Status.FAILED);
                external.setError("Квота ящика Corvex превышена");
                externalImported.save(external);
            }
            return Optional.empty();
        }

        MimeMailParser.ParsedMail parsed;
        try (InputStream in = spool.open(event.rawRef())) {
            parsed = parser.parse(in, true);
        } catch (MessagingException e) {
            throw new IllegalStateException("Не удалось разобрать MIME письма " + event.rawRef(), e);
        }

        boolean infected = "INFECTED".equalsIgnoreCase(event.avStatus());
        MailFolder folder;
        if (infected) {
            folder = ensureFolder(owner.id(), "SPAM", "Junk");
        } else if (external != null && external.getTargetFolder() != null) {
            MailFolder target = folders.findByIdAndOwnerId(external.getTargetFolder().getId(), owner.id()).orElse(null);
            if (target == null) {
                folder = ensureFolder(owner.id(), event.spam() ? "SPAM" : "INBOX", event.spam() ? "Junk" : "INBOX");
            } else if (event.spam() && !external.isPreserveTargetFolder()) {
                folder = ensureFolder(owner.id(), "SPAM", "Junk");
            } else {
                folder = target;
            }
        } else {
            folder = ensureFolder(owner.id(), event.spam() ? "SPAM" : "INBOX", event.spam() ? "Junk" : "INBOX");
        }
        folder = folders.lockById(folder.getId()).orElseThrow();
        long uid = folder.getUidNext();
        folder.setUidNext(uid + 1);
        long modseq = Math.max(1, folder.getHighestModseq()) + 1;
        folder.setHighestModseq(modseq);
        folders.save(folder);

        boolean seen = external != null && external.isSeen();
        String maildirPath;
        try (InputStream in = spool.open(event.rawRef())) {
            maildirPath = maildir.storeMessage(owner.email(), folder.getName(), in, seen).toString();
        }

        MessageMetadata m = new MessageMetadata();
        m.setOwnerId(owner.id());
        m.setFolder(folder);
        m.setUid(uid);
        m.setModseq(modseq);
        m.setMessageIdHeader(truncate(parsed.messageIdHeader(), 998));
        m.setRawRef(event.rawRef());
        m.setSender(truncate(firstNonBlank(parsed.sender(), normalizeAddress(event.mailFrom())), 320));
        m.setSubject(truncate(parsed.subject(), 998));
        m.setSnippet(truncate(parsed.snippet(), 512));
        m.setInternalDate(external != null && external.getSourceInternalDate() != null
                ? external.getSourceInternalDate()
                : (parsed.internalDate() == null ? event.receivedAt() : parsed.internalDate()));
        m.setSizeBytes(event.sizeBytes());
        m.setMaildirPath(maildirPath);
        m.setSeen(seen);
        m.setAnswered(external != null && external.isAnswered());
        m.setFlagged(external != null && external.isFlagged());
        m.setDraft(external != null && external.isDraft());
        m.setDeleted(external != null && external.isDeleted());
        m.setSpamScore(event.spamChecked() ? event.spamScore() : null);
        m.setAvStatus(parseAvStatus(event.avStatus()));
        m = messages.saveAndFlush(m);

        insertRecipients(m.getId(), parsed, recipient, true);
        insertAttachments(m.getId(), parsed, m.getAvStatus().name());
        if (external != null) {
            external.setStatus(ExternalMailImportedMessage.Status.DELIVERED);
            external.setCorvexMessageId(m.getId());
            external.setError(null);
            externalImported.save(external);
        }
        return Optional.of(new DeliveryResult(m.getId(), owner.id(), folder.getId(), uid, owner.email(), parsed));
    }

    /**
     * Копия исходящего письма в Sent отправителя. Для неё используется отдельный spool-ref,
     * поэтому локальная доставка тому же человеку (письмо самому себе) не конфликтует по raw_ref.
     */
    @Transactional
    public Optional<DeliveryResult> storeSentCopy(AuthUserClient.MailboxOwner owner, String rawRef, long sizeBytes)
            throws IOException {
        if (!owner.active() || messages.existsByRawRefAndOwnerId(rawRef, owner.id())) {
            return Optional.empty();
        }
        long used = messages.sumSizeByOwnerId(owner.id());
        if (owner.quotaBytes() > 0 && used + sizeBytes > owner.quotaBytes()) {
            log.warn("Sent-копия не сохранена для {}: квота превышена (used={} incoming={} quota={})",
                    owner.email(), used, sizeBytes, owner.quotaBytes());
            return Optional.empty();
        }

        MimeMailParser.ParsedMail parsed;
        try (InputStream in = spool.open(rawRef)) {
            parsed = parser.parse(in, true);
        } catch (MessagingException e) {
            throw new IllegalStateException("Не удалось разобрать исходящее письмо " + rawRef, e);
        }

        MailFolder folder = ensureFolder(owner.id(), "SENT", "Sent");
        folder = folders.lockById(folder.getId()).orElseThrow();
        long uid = folder.getUidNext();
        folder.setUidNext(uid + 1);
        long modseq = Math.max(1, folder.getHighestModseq()) + 1;
        folder.setHighestModseq(modseq);
        folders.save(folder);

        String maildirPath;
        try (InputStream in = spool.open(rawRef)) {
            maildirPath = maildir.storeMessage(owner.email(), folder.getName(), in).toString();
        }

        MessageMetadata m = new MessageMetadata();
        m.setOwnerId(owner.id());
        m.setFolder(folder);
        m.setUid(uid);
        m.setModseq(modseq);
        m.setMessageIdHeader(truncate(parsed.messageIdHeader(), 998));
        m.setRawRef(rawRef);
        m.setSender(truncate(firstNonBlank(parsed.sender(), owner.email()), 320));
        m.setSubject(truncate(parsed.subject(), 998));
        m.setSnippet(truncate(parsed.snippet(), 512));
        m.setInternalDate(parsed.internalDate() == null ? Instant.now() : parsed.internalDate());
        m.setSizeBytes(sizeBytes);
        m.setMaildirPath(maildirPath);
        m.setSeen(true);
        m.setAnswered(false);
        m.setFlagged(false);
        m.setDraft(false);
        m.setDeleted(false);
        m.setSpamScore(null);
        m.setAvStatus(MessageMetadata.AvStatus.CLEAN);
        m = messages.saveAndFlush(m);

        insertRecipients(m.getId(), parsed, owner.email(), false);
        insertAttachments(m.getId(), parsed, MessageMetadata.AvStatus.CLEAN.name());
        return Optional.of(new DeliveryResult(m.getId(), owner.id(), folder.getId(), uid, owner.email(), parsed));
    }

    @Transactional
    public MailFolder ensureFolder(UUID ownerId, String specialUse, String name) {
        return folders.findFirstByOwnerIdAndSpecialUseIgnoreCase(ownerId, specialUse)
                .orElseGet(() -> {
                    MailFolder f = new MailFolder();
                    f.setOwnerId(ownerId);
                    f.setName(name);
                    f.setSpecialUse(specialUse);
                    f.setUidNext(1);
                    f.setUidValidity(Math.max(1, Instant.now().getEpochSecond()));
                    f.setHighestModseq(1);
                    return folders.saveAndFlush(f);
                });
    }

    private void insertRecipients(UUID messageId, MimeMailParser.ParsedMail parsed, String envelopeRecipient,
                                  boolean addEnvelopeAsBcc) {
        Set<String> seenAddresses = new LinkedHashSet<>();
        parsed.to().forEach(a -> insertRecipient(messageId, "TO", a, seenAddresses));
        parsed.cc().forEach(a -> insertRecipient(messageId, "CC", a, seenAddresses));
        if (addEnvelopeAsBcc && !seenAddresses.contains(envelopeRecipient.toLowerCase(Locale.ROOT))) {
            insertRecipient(messageId, "BCC", new MimeMailParser.ParsedAddress(envelopeRecipient, null), seenAddresses);
        }
    }

    private void insertRecipient(UUID messageId, String kind, MimeMailParser.ParsedAddress address, Set<String> seen) {
        if (address == null || address.address() == null || address.address().isBlank()) {
            return;
        }
        String email = normalizeAddress(address.address());
        seen.add(email.toLowerCase(Locale.ROOT));
        jdbc.update("""
                        insert into message_recipients(message_id, kind, address, display_name)
                        values (?, ?, ?, ?)
                        on conflict do nothing
                        """,
                messageId, kind, truncate(email, 320), truncate(address.displayName(), 256));
    }

    private void insertAttachments(UUID messageId, MimeMailParser.ParsedMail parsed, String avStatus) {
        int partIndex = 0;
        for (MimeMailParser.ParsedAttachment a : parsed.attachments()) {
            if (a.sha256() == null) {
                continue;
            }
            jdbc.update("""
                            insert into attachments(sha256, size_bytes, content_type, av_status)
                            values (?, ?, ?, ?)
                            on conflict (sha256) do update set av_status = excluded.av_status
                            """,
                    a.sha256(), a.sizeBytes(), truncate(a.contentType(), 256), avStatus);
            jdbc.update("""
                            insert into message_attachments(message_id, sha256, file_name, part_index)
                            values (?, ?, ?, ?)
                            on conflict do nothing
                            """,
                    messageId, a.sha256(), truncate(a.fileName(), 512), partIndex++);
        }
    }

    private static MessageMetadata.AvStatus parseAvStatus(String raw) {
        try {
            return MessageMetadata.AvStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return MessageMetadata.AvStatus.ERROR;
        }
    }

    public static String normalizeAddress(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int lt = s.indexOf('<');
        int gt = s.indexOf('>', lt + 1);
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt);
        }
        return s.replace("<", "").replace(">", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
