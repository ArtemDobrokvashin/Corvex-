package ru.corvex.mailstorage.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON-контракты почтового конвейера. Сервисы не делят Java-модуль, поэтому каждое звено
 * держит локальную record-копию с теми же именами полей; на проводе — обычный JSON без __TypeId__.
 */
public final class MailPipelineEvents {

    private MailPipelineEvents() {
    }

    public record AvCheckedEvent(
            String rawRef,
            String mailFrom,
            List<String> recipients,
            String remoteAddress,
            long sizeBytes,
            String sha256,
            Instant receivedAt,
            boolean spam,
            Double spamScore,
            double spamThreshold,
            boolean spamChecked,
            String avStatus,
            String avSignature) {
    }

    public record DeliveredEvent(
            String rawRef,
            UUID messageId,
            UUID ownerId,
            String mailbox,
            UUID folderId,
            long uid,
            Instant deliveredAt) {
    }
}
