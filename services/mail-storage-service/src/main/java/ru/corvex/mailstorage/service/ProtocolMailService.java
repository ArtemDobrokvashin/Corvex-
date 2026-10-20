package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Операции ящика для протокольных сервисов этапа 3 (IMAP/POP3). Единый источник правды —
 * mail_db + Maildir: протокольные сервисы stateless и не имеют своих копий почты.
 * Инварианты IMAP, которые держит этот класс:
 * <ul>
 *   <li>UID выделяется только под PESSIMISTIC_WRITE строки папки и никогда не переиспользуется;</li>
 *   <li>каждый запрос скоупится ownerId из пути — чужая папка/письмо выглядит как 404;</li>
 *   <li>системные папки (specialUse != null) нельзя удалить/переименовать по протоколу;</li>
 *   <li>APPEND проверяет квоту владельца из auth-service (fail-closed при недоступности auth).</li>
 * </ul>
 * Файловые операции внутри транзакции БД — best-effort, как и в остальных сервисах хранилища
 * (глобальной атомарности ФС+БД нет; расхождения ловит reconciler из дорожной карты этапа 5).
 */
@Service
public class ProtocolMailService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolMailService.class);

    private static final Map<String, String> DEFAULT_FOLDERS = Map.of(
            "INBOX", "INBOX",
            "SENT", "Sent",
            "DRAFTS", "Drafts",
            "TRASH", "Trash",
            "SPAM", "Junk",
            "ARCHIVE", "Archive");

    private static final int MAX_LIST_LIMIT = 5000;

    private final MailFolderRepository folders;
    private final MessageMetadataRepository messages;
    private final MailDeliveryService delivery;
    private final MaildirStorage maildir;
    private final RawMessageSpool spool;
    private final MimeMailParser parser;
    private final AuthUserClient auth;

    public ProtocolMailService(MailFolderRepository folders,
                               MessageMetadataRepository messages,
                               MailDeliveryService delivery,
                               MaildirStorage maildir,
                               RawMessageSpool spool,
                               MimeMailParser parser,
                               AuthUserClient auth) {
        this.folders = folders;
        this.messages = messages;
        this.delivery = delivery;
        this.maildir = maildir;
        this.spool = spool;
        this.parser = parser;
        this.auth = auth;
    }

    public record RawMessage(InputStream stream, ProtocolDtos.MessageMeta meta) {
    }

    // ------------------------------------------------------------------ folders

    @Transactional
    public List<ProtocolDtos.FolderInfo> listFolders(UUID ownerId) {
        DEFAULT_FOLDERS.forEach((specialUse, name) -> delivery.ensureFolder(ownerId, specialUse, name));
        return folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId).stream()
                .map(this::toFolderInfo)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProtocolDtos.FolderInfo folderStatus(UUID ownerId, UUID folderId) {
        return toFolderInfo(loadFolder(ownerId, folderId));
    }

    @Transactional
    public ProtocolDtos.FolderInfo createFolder(UUID ownerId, ProtocolDtos.CreateFolderRequest req) {
        String name = validateFolderName(req.name());
        MailFolder parent = req.parentId() == null ? null : loadFolder(ownerId, req.parentId());
        if (folders.existsSibling(ownerId, req.parentId(), name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Папка с таким именем уже существует");
        }
        MailFolder f = new MailFolder();
        f.setOwnerId(ownerId);
        f.setParent(parent);
        f.setName(name);
        f.setSpecialUse(null);
        f.setUidNext(1);
        f.setUidValidity(Math.max(1, Instant.now().getEpochSecond()));
        return toFolderInfo(folders.saveAndFlush(f));
    }

    @Transactional
    public ProtocolDtos.FolderInfo renameFolder(UUID ownerId, UUID folderId, ProtocolDtos.RenameFolderRequest req)
            throws IOException {
        MailFolder folder = loadFolder(ownerId, folderId);
        if (folder.getSpecialUse() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Системную папку переименовать нельзя");
        }
        String newName = validateFolderName(req.name());
        UUID parentId = folder.getParent() == null ? null : folder.getParent().getId();
        if (!folder.getName().equalsIgnoreCase(newName) && folders.existsSibling(ownerId, parentId, newName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Папка с таким именем уже существует");
        }
        String oldName = folder.getName();
        if (!oldName.equals(newName)) {
            AuthUserClient.MailboxOwner owner = loadOwner(ownerId);
            maildir.renameFolderDir(owner.email(), oldName, newName);
            // Пути писем содержат сегмент каталога папки: <mailbox>/<folder>/new|cur/<file>
            for (MessageMetadata m : messages.findAllByFolder_IdAndOwnerId(folderId, ownerId)) {
                m.setMaildirPath(replaceFolderSegment(m.getMaildirPath(), newName));
                messages.save(m);
            }
            folder.setName(newName);
            folders.save(folder);
        }
        return toFolderInfo(folder);
    }

    @Transactional
    public void deleteFolder(UUID ownerId, UUID folderId) {
        MailFolder folder = loadFolder(ownerId, folderId);
        if (folder.getSpecialUse() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Системную папку удалить нельзя");
        }
        if (folders.existsByParent_Id(folderId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала удалите вложенные папки");
        }
        for (MessageMetadata m : messages.findAllByFolder_IdAndOwnerId(folderId, ownerId)) {
            deleteFileQuietly(m);
            messages.delete(m);
        }
        folders.delete(folder);
        auth.findById(ownerId).ifPresent(owner -> {
            try {
                maildir.deleteFolderDir(owner.email(), folder.getName());
            } catch (IOException e) {
                log.warn("Не удалось удалить каталог папки {} владельца {}: {}", folderId, ownerId, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ messages

    @Transactional(readOnly = true)
    public List<ProtocolDtos.MessageMeta> listMessages(UUID ownerId, UUID folderId, long afterUid, int limit) {
        loadFolder(ownerId, folderId);
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? 1000 : limit, MAX_LIST_LIMIT));
        return messages.findByFolder_IdAndOwnerIdAndUidGreaterThanOrderByUidAsc(
                        folderId, ownerId, Math.max(0, afterUid), PageRequest.of(0, safeLimit)).stream()
                .map(ProtocolMailService::toMeta)
                .toList();
    }

    /** Сырой .eml по (папка, UID) — IMAP FETCH BODY[]. Поток закрывает вызывающий контроллер. */
    @Transactional(readOnly = true)
    public RawMessage openByUid(UUID ownerId, UUID folderId, long uid) throws IOException {
        MessageMetadata m = messages.findByFolder_IdAndUidAndOwnerId(folderId, uid, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Письмо не найдено"));
        return new RawMessage(maildir.openMessage(m.getMaildirPath()), toMeta(m));
    }

    /** Сырой .eml по глобальному UUID письма — POP3 RETR (UIDL = UUID строки, стабилен между сессиями). */
    @Transactional(readOnly = true)
    public RawMessage openById(UUID ownerId, UUID messageId) throws IOException {
        MessageMetadata m = loadMessage(ownerId, messageId);
        return new RawMessage(maildir.openMessage(m.getMaildirPath()), toMeta(m));
    }

    @Transactional
    public ProtocolDtos.MessageMeta updateFlags(UUID ownerId, UUID folderId, long uid, ProtocolDtos.FlagsUpdate flags) {
        MessageMetadata m = messages.findByFolder_IdAndUidAndOwnerId(folderId, uid, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Письмо не найдено"));
        if (flags.seen() != null) m.setSeen(flags.seen());
        if (flags.answered() != null) m.setAnswered(flags.answered());
        if (flags.flagged() != null) m.setFlagged(flags.flagged());
        if (flags.draft() != null) m.setDraft(flags.draft());
        if (flags.deleted() != null) m.setDeleted(flags.deleted());
        return toMeta(messages.save(m));
    }

    @Transactional
    public ProtocolDtos.ExpungeResponse expunge(UUID ownerId, UUID folderId, ProtocolDtos.ExpungeRequest req) {
        loadFolder(ownerId, folderId);
        List<MessageMetadata> victims;
        if (req == null || req.uids() == null || req.uids().isEmpty()) {
            victims = messages.findAllByFolder_IdAndOwnerIdAndDeletedTrue(folderId, ownerId);
        } else {
            victims = new ArrayList<>();
            for (Long uid : req.uids()) {
                messages.findByFolder_IdAndUidAndOwnerId(folderId, uid, ownerId)
                        .filter(MessageMetadata::isDeleted) // UID EXPUNGE трогает только \Deleted
                        .ifPresent(victims::add);
            }
        }
        List<Long> expunged = new ArrayList<>();
        for (MessageMetadata m : victims) {
            deleteFileQuietly(m);
            messages.delete(m);
            expunged.add(m.getUid());
        }
        return new ProtocolDtos.ExpungeResponse(expunged);
    }

    /** Жёсткое удаление одного письма — POP3 DELE, применённый на QUIT (флага \Deleted в POP3 нет). */
    @Transactional
    public void hardDeleteMessage(UUID ownerId, UUID messageId) {
        MessageMetadata m = loadMessage(ownerId, messageId);
        deleteFileQuietly(m);
        messages.delete(m);
    }

    /**
     * IMAP APPEND: клиент (Thunderbird/Outlook) кладёт письмо в Sent/Drafts после отправки
     * или при миграции ящика. Тело проходит через spool (лимит размера там же), затем в Maildir.
     */
    @Transactional
    public ProtocolDtos.AppendResponse append(UUID ownerId, UUID folderId, InputStream eml,
                                              Instant internalDate, Set<String> flagSet) throws IOException {
        AuthUserClient.MailboxOwner owner = loadOwner(ownerId);
        RawMessageSpool.StoredRawMessage stored;
        try {
            stored = spool.store(eml);
        } catch (RawMessageSpool.MessageTooLargeException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }
        try {
            long used = messages.sumSizeByOwnerId(ownerId);
            if (owner.quotaBytes() > 0 && used + stored.sizeBytes() > owner.quotaBytes()) {
                throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, "Квота ящика превышена");
            }
            MimeMailParser.ParsedMail parsed;
            try (InputStream in = spool.open(stored.rawRef())) {
                parsed = parser.parse(in, false);
            } catch (MessagingException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Некорректное MIME-письмо");
            }
            MailFolder folder = folders.lockById(loadFolder(ownerId, folderId).getId()).orElseThrow();
            long uid = folder.getUidNext();
            folder.setUidNext(uid + 1);
            folders.save(folder);

            boolean seen = flagSet.contains("\\seen");
            String maildirPath;
            try (InputStream in = spool.open(stored.rawRef())) {
                maildirPath = maildir.storeMessage(owner.email(), folder.getName(), in, seen).toString();
            }
            MessageMetadata m = new MessageMetadata();
            m.setOwnerId(ownerId);
            m.setFolder(folder);
            m.setUid(uid);
            m.setMessageIdHeader(truncate(parsed.messageIdHeader(), 998));
            m.setSender(truncate(parsed.sender() == null ? owner.email() : parsed.sender(), 320));
            m.setSubject(truncate(parsed.subject() == null ? "" : parsed.subject(), 998));
            m.setSnippet(truncate(parsed.snippet(), 512));
            m.setInternalDate(internalDate != null ? internalDate
                    : (parsed.internalDate() != null ? parsed.internalDate() : Instant.now()));
            m.setSizeBytes(stored.sizeBytes());
            m.setMaildirPath(maildirPath);
            m.setSeen(seen);
            m.setAnswered(flagSet.contains("\\answered"));
            m.setFlagged(flagSet.contains("\\flagged"));
            m.setDraft(flagSet.contains("\\draft"));
            m.setDeleted(flagSet.contains("\\deleted"));
            m.setSpamScore(null);
            m.setAvStatus(MessageMetadata.AvStatus.CLEAN); // письмо кладёт сам владелец, как Sent-копию
            m = messages.saveAndFlush(m);
            return new ProtocolDtos.AppendResponse(m.getId(), uid);
        } finally {
            try {
                spool.delete(stored.rawRef());
            } catch (IOException e) {
                log.warn("Не удалось очистить spool {} после APPEND: {}", stored.rawRef(), e.getMessage());
            }
        }
    }

    @Transactional
    public ProtocolDtos.CopyResponse copyMessages(UUID ownerId, UUID folderId, ProtocolDtos.CopyRequest req)
            throws IOException {
        loadFolder(ownerId, folderId);
        MailFolder target = folders.lockById(loadFolder(ownerId, req.targetFolderId()).getId()).orElseThrow();
        List<Long> sourceUids = new ArrayList<>();
        List<Long> targetUids = new ArrayList<>();
        for (Long uid : req.uids() == null ? List.<Long>of() : req.uids()) {
            MessageMetadata src = messages.findByFolder_IdAndUidAndOwnerId(folderId, uid, ownerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Письмо не найдено: uid=" + uid));
            long newUid = target.getUidNext();
            target.setUidNext(newUid + 1);
            folders.save(target);
            if (req.move()) {
                Path newPath = maildir.moveMessageToFolder(src.getMaildirPath(), target.getName(), src.isSeen());
                src.setFolder(target);
                src.setUid(newUid);
                src.setMaildirPath(newPath.toString());
                messages.save(src);
            } else {
                Path newPath = maildir.copyMessageToFolder(src.getMaildirPath(), target.getName(), src.isSeen());
                MessageMetadata copy = new MessageMetadata();
                copy.setOwnerId(ownerId);
                copy.setFolder(target);
                copy.setUid(newUid);
                copy.setMessageIdHeader(src.getMessageIdHeader());
                copy.setSender(src.getSender());
                copy.setSubject(src.getSubject());
                copy.setSnippet(src.getSnippet());
                copy.setInternalDate(src.getInternalDate());
                copy.setSizeBytes(src.getSizeBytes());
                copy.setMaildirPath(newPath.toString());
                copy.setSeen(src.isSeen());
                copy.setAnswered(src.isAnswered());
                copy.setFlagged(src.isFlagged());
                copy.setDraft(src.isDraft());
                copy.setDeleted(false); // копия не должна родиться невидимой под \Deleted
                copy.setSpamScore(src.getSpamScore());
                copy.setAvStatus(src.getAvStatus());
                messages.saveAndFlush(copy);
            }
            sourceUids.add(uid);
            targetUids.add(newUid);
        }
        return new ProtocolDtos.CopyResponse(sourceUids, targetUids);
    }

    @Transactional(readOnly = true)
    public ProtocolDtos.QuotaView quota(UUID ownerId) {
        AuthUserClient.MailboxOwner owner = loadOwner(ownerId);
        return new ProtocolDtos.QuotaView(
                messages.sumSizeByOwnerId(ownerId), owner.quotaBytes(), messages.countByOwnerId(ownerId));
    }

    // ------------------------------------------------------------------ helpers

    private MailFolder loadFolder(UUID ownerId, UUID folderId) {
        return folders.findByIdAndOwnerId(folderId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Папка не найдена"));
    }

    private MessageMetadata loadMessage(UUID ownerId, UUID messageId) {
        return messages.findByIdAndOwnerId(messageId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Письмо не найдено"));
    }

    private AuthUserClient.MailboxOwner loadOwner(UUID ownerId) {
        return auth.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "Профиль владельца ящика недоступен"));
    }

    private ProtocolDtos.FolderInfo toFolderInfo(MailFolder f) {
        return new ProtocolDtos.FolderInfo(
                f.getId(), f.getParent() == null ? null : f.getParent().getId(),
                f.getName(), f.getSpecialUse(), f.getUidNext(), f.getUidValidity(),
                messages.countByFolder_Id(f.getId()), messages.countByFolder_IdAndSeenFalse(f.getId()));
    }

    static ProtocolDtos.MessageMeta toMeta(MessageMetadata m) {
        return new ProtocolDtos.MessageMeta(
                m.getId(), m.getUid(), m.getMessageIdHeader(), m.getSender(), m.getSubject(),
                m.getInternalDate(), m.getSizeBytes(),
                m.isSeen(), m.isAnswered(), m.isFlagged(), m.isDraft(), m.isDeleted());
    }

    private void deleteFileQuietly(MessageMetadata m) {
        try {
            maildir.deleteMessage(m.getMaildirPath());
        } catch (IOException e) {
            // Строка БД — источник правды; осиротевший файл подберёт reconciler, письмо не «воскреснет».
            log.warn("Не удалось удалить файл письма {} ({}): {}", m.getId(), m.getMaildirPath(), e.getMessage());
        }
    }

    static String validateFolderName(String raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Имя папки не задано");
        }
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 256
                || name.contains("/") || name.contains("\\") || name.contains("%") || name.contains("*")
                || name.contains("..") || name.chars().anyMatch(c -> c < 0x20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимое имя папки");
        }
        if ("INBOX".equalsIgnoreCase(name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "INBOX — системная папка");
        }
        return name;
    }

    /** Путь Maildir: сегмент[0]=ящик, сегмент[1]=папка — при RENAME меняется только сегмент папки. */
    static String replaceFolderSegment(String maildirPath, String newFolderName) {
        if (maildirPath == null) {
            return null;
        }
        String normalized = maildirPath.replace('\\', '/');
        String[] parts = normalized.split("/", 3);
        if (parts.length < 3) {
            return maildirPath;
        }
        return parts[0] + "/" + sanitizeSegment(newFolderName) + "/" + parts[2];
    }

    /** Зеркалит MaildirStorage.sanitize: каталог на ФС создан именно с таким именем. */
    private static String sanitizeSegment(String segment) {
        return segment.replaceAll("[^A-Za-z0-9@._-]", "_");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Нормализация флагов IMAP из заголовка append-запроса: "\Seen \Draft" -> {"\seen","\draft"}. */
    public static Set<String> parseFlags(String header) {
        if (header == null || header.isBlank()) {
            return Set.of();
        }
        Set<String> out = new java.util.HashSet<>();
        for (String f : header.split("[\\s,]+")) {
            if (!f.isBlank()) {
                out.add(f.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }
}
