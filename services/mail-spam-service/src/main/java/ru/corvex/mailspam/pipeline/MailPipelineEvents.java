package ru.corvex.mailspam.pipeline;

import java.time.Instant;
import java.util.List;

/** Локальные копии JSON-контрактов конвейера (см. docs/03-api-contracts.md). */
public final class MailPipelineEvents {

    private MailPipelineEvents() {
    }

    public record InboundReceivedEvent(
            String rawRef,
            String mailFrom,
            List<String> recipients,
            String remoteAddress,
            long sizeBytes,
            String sha256,
            Instant receivedAt) {
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
}
