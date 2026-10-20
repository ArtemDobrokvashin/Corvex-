package ru.corvex.mailsmtp.outbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Локальные копии JSON-контрактов исходящей очереди (storage ↔ smtp worker). */
public final class OutboundEvents {

    private OutboundEvents() {
    }

    public record OutboundQueuedEvent(
            String rawRef,
            String mailFrom,
            List<String> recipients,
            String messageIdHeader,
            UUID senderId,
            Instant queuedAt,
            int attempt) {
    }

    public record OutboundSentEvent(
            String rawRef,
            String mailFrom,
            List<String> recipients,
            String messageIdHeader,
            UUID senderId,
            Instant sentAt) {
    }

    public record OutboundBouncedEvent(
            String rawRef,
            String mailFrom,
            String recipient,
            String diagnostic,
            String messageIdHeader,
            UUID senderId,
            Instant failedAt) {
    }
}
