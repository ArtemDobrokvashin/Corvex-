package ru.corvex.mailav.pipeline;

import java.time.Instant;
import java.util.List;

/** Локальные копии JSON-контрактов конвейера (см. docs/03-api-contracts.md). */
public final class MailPipelineEvents {

    private MailPipelineEvents() {
    }

    public record SpamCheckedEvent(
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
            boolean spamChecked) {
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
}
