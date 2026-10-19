package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.domain.MessageMetadata;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.repository.MailFolderRepository;
import ru.corvex.mailstorage.repository.MessageMetadataRepository;
import ru.corvex.mailstorage.storage.MaildirStorage;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-side webmail API: папки, курсорные списки, чтение письма из Maildir, флаги. */
@Service
public class MailApiService {

    private static final Map<String, String> DEFAULT_FOLDERS = Map.of(
            "INBOX", "INBOX",
            "SENT", "Sent",
            "DRAFTS", "Drafts",
            "TRASH", "Trash",
            "SPAM", "Junk",
            "ARCHIVE", "Archive");

    private final MailFolderRepository folders;
    private final MessageMetadataRepository messages;
    private final MailDeliveryService delivery;
    private final MaildirStorage maildir;
    private final MimeMailParser parser;
    private final JdbcTemplate jdbc;

    public MailApiService(MailFolderRepository folders,
                          MessageMetadataRepository messages,
                          MailDeliveryService delivery,
                          MaildirStorage maildir,
                          MimeMailParser parser,
                          JdbcTemplate jdbc) {
        this.folders = folders;
        this.messages = messages;
        this.delivery = delivery;
        this.maildir = maildir;
        this.parser = parser;
        this.jdbc = jdbc;
    }

    @Transactional
    public List<MailDtos.FolderView> listFolders(UUID ownerId) {
        DEFAULT_FOLDERS.forEach((specialUse, name) -> delivery.ensureFolder(ownerId, specialUse, name));
        return folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId).stream()
                .map(f -> new MailDtos.FolderView(
                        f.getId(), f.getName(), f.getSpecialUse(),
                        messages.countByFolder_Id(f.getId()),
                        messages.countByFolder_IdAndSeenFalse(f.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MailDtos.MessageListItem> listMessages(UUID ownerId, UUID folderId, Long cursorUid, int limit) {
        MailFolder folder = folders.findByIdAndOwnerId(folderId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Папка не найдена"));
        int safeLimit = Math.min(100, Math.max(1, limit));
        long cursor = cursorUid == null ? Long.MAX_VALUE : cursorUid;
        List<MessageMetadata> rows = messages.listFolderBeforeUid(folder.getId(), ownerId, cursor, PageRequest.of(0, safeLimit));
        Set<UUID> withAttachments = attachmentMessageIds(rows);
        return rows.stream().map(m -> toListItem(m, withAttachments.contains(m.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public MailDtos.MessageDetail getMessage(UUID ownerId, UUID messageId) throws IOException {
        MessageMetadata m = loadOwned(ownerId, messageId);
        MimeMailParser.ParsedMail parsed;
        try (InputStream in = maildir.openMessage(m.getMaildirPath())) {
            parsed = parser.parse(in, false);
        } catch (MessagingException e) {
            throw new IOException("Не удалось разобрать письмо", e);
        }
        List<MailDtos.AttachmentView> attachments = jdbc.query("""
                        select ma.sha256, ma.file_name, a.size_bytes, a.content_type, a.av_status
                        from message_attachments ma
                        join attachments a on a.sha256 = ma.sha256
                        where ma.message_id = ?
                        order by ma.part_index
                        """,
                (rs, row) -> new MailDtos.AttachmentView(
                        rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)),
                m.getId());
        return new MailDtos.MessageDetail(
                toListItem(m, !attachments.isEmpty()),
                parsed.to().stream().map(MimeMailParser.ParsedAddress::address).toList(),
                parsed.cc().stream().map(MimeMailParser.ParsedAddress::address).toList(),
                parsed.bodyText(),
                parsed.bodyHtml(),
                attachments);
    }

    @Transactional
    public MailDtos.MessageListItem updateFlags(UUID ownerId, UUID messageId, MailDtos.UpdateFlagsRequest req) {
        MessageMetadata m = loadOwned(ownerId, messageId);
        if (req.seen() != null) {
            m.setSeen(req.seen());
        }
        if (req.flagged() != null) {
            m.setFlagged(req.flagged());
        }
        if (req.answered() != null) {
            m.setAnswered(req.answered());
        }
        if (req.deleted() != null) {
            m.setDeleted(req.deleted());
        }
        m = messages.save(m);
        return toListItem(m, hasAttachments(m.getId()));
    }

    @Transactional
    public MailDtos.MessageListItem moveMessage(UUID ownerId, UUID messageId, UUID targetFolderId) throws IOException {
        MessageMetadata m = loadOwned(ownerId, messageId);
        MailFolder target = folders.findByIdAndOwnerId(targetFolderId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Папка назначения не найдена"));
        if (m.getFolder().getId().equals(target.getId())) {
            return toListItem(m, hasAttachments(m.getId()));
        }
        target = folders.lockById(target.getId()).orElseThrow();
        long uid = target.getUidNext();
        target.setUidNext(uid + 1);
        folders.save(target);

        String newPath = maildir.moveMessageToFolder(m.getMaildirPath(), target.getName(), m.isSeen()).toString();
        m.setFolder(target);
        m.setUid(uid);
        m.setMaildirPath(newPath);
        m = messages.save(m);
        return toListItem(m, hasAttachments(m.getId()));
    }

    private MessageMetadata loadOwned(UUID ownerId, UUID messageId) {
        return messages.findByIdAndOwnerId(messageId, ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Письмо не найдено"));
    }

    private MailDtos.MessageListItem toListItem(MessageMetadata m, boolean hasAttachments) {
        return new MailDtos.MessageListItem(
                m.getId(), m.getFolder().getId(), m.getUid(), m.getSender(), m.getSubject(), m.getSnippet(),
                m.getInternalDate(), m.getSizeBytes(), m.isSeen(), m.isFlagged(), m.isAnswered(),
                hasAttachments, m.getAvStatus().name(), m.getSpamScore());
    }

    private boolean hasAttachments(UUID messageId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from message_attachments where message_id = ?", Integer.class, messageId);
        return count != null && count > 0;
    }

    private Set<UUID> attachmentMessageIds(List<MessageMetadata> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        String placeholders = rows.stream().map(r -> "?").collect(Collectors.joining(","));
        Object[] ids = rows.stream().map(MessageMetadata::getId).toArray();
        return Set.copyOf(jdbc.queryForList(
                "select distinct message_id from message_attachments where message_id in (" + placeholders + ")",
                UUID.class, ids));
    }
}
