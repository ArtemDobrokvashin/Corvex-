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

    public record CreateFolderRequest(String name, UUID parentId) {
    }

    public record QuotaView(long usedBytes, long quotaBytes, long messageCount) {
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

    public record AttachmentView(int partIndex, String sha256, String fileName, long sizeBytes, String contentType, String avStatus) {
    }

    public record MessageDetail(
            MessageListItem summary,
            List<String> to,
            List<String> cc,
            String bodyText,
            String bodyHtml,
            List<AttachmentView> attachments) {
    }

    public record InlineImageRequest(String cid) { }

    public record UpdateFlagsRequest(Boolean seen, Boolean flagged, Boolean answered, Boolean deleted) {
    }

    public record MoveMessageRequest(UUID targetFolderId, UUID expectedFolderId, Long expectedUid) {
        public MoveMessageRequest(UUID targetFolderId) { this(targetFolderId, null, null); }
    }

    public record SendAttachment(String fileName, String contentType, String contentBase64) {
    }

    public record SendMessageRequest(
            List<String> to,
            List<String> cc,
            List<String> bcc,
            String subject,
            String bodyText,
            String bodyHtml,
            List<SendAttachment> attachments,
            Boolean saveToSent,
            UUID externalAccountId,
            /** Письмо, на которое отвечаем: из него берутся In-Reply-To и References. */
            UUID inReplyToMessageId,
            /** «Отправить позже»: null — отправить сразу, иначе время отправки. */
            Instant sendAt) {
    }

    /** «Отложить до…»: время возврата в UTC. */
    public record SnoozeRequest(Instant wakeAt) {
    }

    /** Письмо, которое сейчас скрыто из списка до указанного момента. */
    public record SnoozedView(UUID messageId, String subject, String sender, UUID folderId, Instant wakeAt) {
    }

    /** Отложенная отправка: письмо уже собрано, но ещё не ушло. */
    public record ScheduleSendRequest(Instant sendAt) {
    }

    public record ScheduledSendView(UUID id, Instant sendAt, String status) {
    }

    /** Результат полнотекстового поиска: идентификатор письма и короткое превью. */
    public record SearchResultView(
            UUID messageId,
            UUID folderId,
            long uid,
            String sender,
            String subject,
            String snippet,
            String internalDate) {
    }

    public record SendMessageResult(
            String messageIdHeader,
            UUID sentMessageId,
            List<String> localRecipients,
            List<String> externalRecipients,
            String fromAddress,
            UUID externalAccountId,
            /** Заполнен только для отложенного письма: по нему отправку отменяют. */
            UUID scheduledSendId,
            /** Время запланированной отправки; null — письмо ушло сразу. */
            Instant scheduledSendAt) {
    }
}
