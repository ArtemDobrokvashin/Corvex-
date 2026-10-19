package ru.corvex.mailstorage.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Публичные DTO webmail API этапа 2 (gateway: /api/v1/mail/**). */
public final class MailDtos {

    private MailDtos() {
    }

    public record FolderView(UUID id, String name, String specialUse, long total, long unread) {
    }

    public record MessageListItem(
            UUID id,
            UUID folderId,
            long uid,
            String sender,
            String subject,
            String snippet,
            Instant date,
            long sizeBytes,
            boolean seen,
            boolean flagged,
            boolean answered,
            boolean hasAttachments,
            String avStatus,
            Double spamScore) {
    }

    public record AttachmentView(String sha256, String fileName, long sizeBytes, String contentType, String avStatus) {
    }

    public record MessageDetail(
            MessageListItem summary,
            List<String> to,
            List<String> cc,
            String bodyText,
            String bodyHtml,
            List<AttachmentView> attachments) {
    }

    public record UpdateFlagsRequest(Boolean seen, Boolean flagged, Boolean answered, Boolean deleted) {
    }
}
