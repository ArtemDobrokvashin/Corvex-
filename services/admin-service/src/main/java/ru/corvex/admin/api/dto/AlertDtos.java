package ru.corvex.admin.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Настройки доставки алертов. Публичный View намеренно не содержит полей с
 * секретами: только configured/source. Секреты из Request доступны только на запись.
 */
public final class AlertDtos {

    private AlertDtos() {
    }

    public record SecretState(boolean configured, String source) {
    }

    /** Полная замена несекретных полей; blank secret = сохранить прежний. */
    public record AlertSettingsRequest(
            int repeatNormalMinutes,
            int repeatCriticalMinutes,
            Map<String, Boolean> categories,
            boolean internalEmailEnabled,
            String internalRecipients,
            String internalFrom,
            boolean externalEmailEnabled,
            String externalEmailRecipients,
            String externalSmtpHost,
            int externalSmtpPort,
            String externalSmtpTlsMode,
            String externalSmtpUsername,
            String externalSmtpPassword,
            boolean clearExternalSmtpPassword,
            String externalSmtpFrom,
            boolean telegramEnabled,
            String telegramChatId,
            String telegramBotToken,
            boolean clearTelegramBotToken,
            boolean maxEnabled,
            String maxChatId,
            String maxUserId,
            String maxBotToken,
            boolean clearMaxBotToken) {
    }

    public record AlertSettingsView(
            long revision,
            Instant updatedAt,
            String source,
            int repeatNormalMinutes,
            int repeatCriticalMinutes,
            Map<String, Boolean> categories,
            boolean internalEmailEnabled,
            String internalRecipients,
            String internalFrom,
            boolean externalEmailEnabled,
            String externalEmailRecipients,
            String externalSmtpHost,
            int externalSmtpPort,
            String externalSmtpTlsMode,
            String externalSmtpUsername,
            SecretState externalSmtpPassword,
            String externalSmtpFrom,
            boolean telegramEnabled,
            String telegramChatId,
            SecretState telegramBotToken,
            boolean maxEnabled,
            String maxChatId,
            String maxUserId,
            SecretState maxBotToken) {
    }

    public record AlertConsumerStatus(
            String consumer,
            String status,
            Long revision,
            String source,
            String refresh,
            Instant updatedAt) {
    }

    public record AlertRuntimeStatus(long revision, List<AlertConsumerStatus> consumers) {
    }

    /** Несекретная policy для внутренней почты notification-service. */
    public record NotificationPolicy(
            long revision,
            Instant updatedAt,
            int repeatNormalSeconds,
            int repeatCriticalSeconds,
            Map<String, Boolean> categories,
            boolean internalEmailEnabled,
            String internalRecipients,
            String internalFrom) {
    }

    /** Эффективная конфигурация внешнего relay; доступна только по internal token. */
    public record RelayConfig(
            long revision,
            Instant updatedAt,
            int repeatNormalSeconds,
            int repeatCriticalSeconds,
            Map<String, Boolean> categories,
            boolean externalEmailEnabled,
            String externalEmailRecipients,
            String externalSmtpHost,
            int externalSmtpPort,
            String externalSmtpTlsMode,
            String externalSmtpUsername,
            String externalSmtpPassword,
            String externalSmtpFrom,
            boolean telegramEnabled,
            String telegramChatId,
            String telegramBotToken,
            boolean maxEnabled,
            String maxChatId,
            String maxUserId,
            String maxBotToken) {
    }
}
