package ru.corvex.admin.service;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.api.dto.AlertDtos.AlertConsumerStatus;
import ru.corvex.admin.api.dto.AlertDtos.AlertRuntimeStatus;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsRequest;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsView;
import ru.corvex.admin.api.dto.AlertDtos.NotificationPolicy;
import ru.corvex.admin.api.dto.AlertDtos.RelayConfig;
import ru.corvex.admin.api.dto.AlertDtos.SecretState;
import ru.corvex.admin.service.AlertConfigRepository.Config;
import ru.corvex.admin.service.AlertConfigRepository.Stored;
import ru.corvex.admin.service.AuditService.Actor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/** Runtime-настройки алертов: registry этой страницы не используется. */
@Service
public class AlertSettingsService {

    public static final List<String> CATEGORY_CODES = List.of(
            "ServiceDown", "HighHttp5xxRate", "MailQueueBacklog", "DiskSpaceLow", "JvmMemoryPressure");
    private static final int MAX_REPEAT_MINUTES = 43_200;

    private final AlertConfigRepository repository;
    private final AuditService audit;
    private final AlertSecretCipher cipher;
    private final Environment env;
    private final RestClient statusClient;

    public AlertSettingsService(AlertConfigRepository repository, AuditService audit,
                                AlertSecretCipher cipher, Environment env) {
        this.repository = repository;
        this.audit = audit;
        this.cipher = cipher;
        this.env = env;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(600);
        factory.setReadTimeout(900);
        this.statusClient = RestClient.builder().requestFactory(factory).build();
    }

    private record Effective(Config config, long revision, Instant updatedAt, String source,
                             int repeatNormalSeconds, int repeatCriticalSeconds) {
    }

    public AlertSettingsView settings() {
        return toView(current());
    }

    public AlertRuntimeStatus runtimeStatus() {
        long revision = current().revision();
        return new AlertRuntimeStatus(revision, List.of(
                notificationStatus(revision), relayStatus(revision)));
    }

    record NotificationStatusPayload(long revision, String source, Instant lastSuccessAt,
                                     boolean internalEmailEnabled, String internalRecipients) {
    }

    private AlertConsumerStatus notificationStatus(long expectedRevision) {
        String url = env("CORVEX_NOTIFICATION_SERVICE_URL", "http://notification-service:8091");
        try {
            NotificationStatusPayload payload = statusClient.get()
                    .uri(url + "/internal/alerts/runtime-status")
                    .retrieve().body(NotificationStatusPayload.class);
            if (payload == null) throw new IllegalStateException("Empty notification status");
            return consumerStatus("notification-service", expectedRevision, payload.revision(),
                    payload.source(), payload.lastSuccessAt() == null ? "NOT_ATTEMPTED" : "SUCCESS",
                    payload.lastSuccessAt());
        } catch (Exception e) {
            return unavailable("notification-service");
        }
    }

    private AlertConsumerStatus relayStatus(long expectedRevision) {
        String url = env("CORVEX_ALERT_RELAY_URL", "http://alert-relay:8092");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> health = statusClient.get().uri(url + "/healthz")
                    .retrieve().body(Map.class);
            Object rawRuntime = health == null ? null : health.get("runtime");
            if (!(rawRuntime instanceof Map<?, ?> runtime)) {
                throw new IllegalStateException("Missing relay runtime status");
            }
            long revision = runtime.get("revision") instanceof Number number ? number.longValue() : -1;
            String source = runtime.get("source") == null ? "UNKNOWN" : String.valueOf(runtime.get("source"));
            String refresh = runtime.get("refresh") == null ? "UNKNOWN" : String.valueOf(runtime.get("refresh"));
            Instant updatedAt = parseInstant(runtime.get("updatedAt"));
            return consumerStatus("alert-relay", expectedRevision, revision, source, refresh, updatedAt);
        } catch (Exception e) {
            return unavailable("alert-relay");
        }
    }

    private static AlertConsumerStatus consumerStatus(String consumer, long expected, long actual,
                                                       String source, String refresh, Instant updatedAt) {
        return new AlertConsumerStatus(consumer, actual == expected ? "APPLIED" : "PENDING",
                actual, source, refresh, updatedAt);
    }

    private static AlertConsumerStatus unavailable(String consumer) {
        return new AlertConsumerStatus(consumer, "UNAVAILABLE", null, "NONE", "UNAVAILABLE", null);
    }

    private static Instant parseInstant(Object value) {
        try {
            return value == null ? null : Instant.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public synchronized AlertSettingsView replace(AlertSettingsRequest request, Actor actor) {
        if (request == null) throw ApiException.badRequest("Пустая конфигурация алертов");
        Effective current = current();
        Config previous = current.config();
        Config candidate = new Config(
                request.repeatNormalMinutes(),
                request.repeatCriticalMinutes(),
                normalizedCategories(request.categories()),
                request.internalEmailEnabled(),
                normalize(request.internalRecipients()),
                normalize(request.internalFrom()),
                request.externalEmailEnabled(),
                normalize(request.externalEmailRecipients()),
                normalize(request.externalSmtpHost()),
                request.externalSmtpPort(),
                normalize(request.externalSmtpTlsMode()).toLowerCase(),
                normalize(request.externalSmtpUsername()),
                updatedSecret(request.externalSmtpPassword(), request.clearExternalSmtpPassword(),
                        previous.externalSmtpPasswordEnc(), "SMTP password"),
                normalize(request.externalSmtpFrom()),
                request.telegramEnabled(),
                normalize(request.telegramChatId()),
                updatedSecret(request.telegramBotToken(), request.clearTelegramBotToken(),
                        previous.telegramBotTokenEnc(), "Telegram token"),
                request.maxEnabled(),
                normalize(request.maxChatId()),
                normalize(request.maxUserId()),
                updatedSecret(request.maxBotToken(), request.clearMaxBotToken(),
                        previous.maxBotTokenEnc(), "MAX token"));
        try {
            validate(candidate);
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }

        List<String> changedSecrets = new ArrayList<>();
        if (!Objects.equals(candidate.externalSmtpPasswordEnc(), previous.externalSmtpPasswordEnc())) {
            changedSecrets.add("externalSmtpPassword");
        }
        if (!Objects.equals(candidate.telegramBotTokenEnc(), previous.telegramBotTokenEnc())) {
            changedSecrets.add("telegramBotToken");
        }
        if (!Objects.equals(candidate.maxBotTokenEnc(), previous.maxBotTokenEnc())) {
            changedSecrets.add("maxBotToken");
        }
        Stored saved = repository.save(candidate, actor);
        Effective effective = fromStored(saved);
        audit.record(actor, "ALERT_SETTINGS_CHANGED", "alert_runtime_config", "1", Map.of(
                "revision", saved.revision(),
                "changedSecrets", List.copyOf(changedSecrets),
                "disabledCategories", CATEGORY_CODES.stream()
                        .filter(code -> !candidate.categories().get(code)).toList()));
        return toView(effective);
    }

    public NotificationPolicy notificationPolicy() {
        Effective current = current();
        Config c = current.config();
        return new NotificationPolicy(current.revision(), current.updatedAt(),
                current.repeatNormalSeconds(), current.repeatCriticalSeconds(), c.categories(),
                c.internalEmailEnabled(), c.internalRecipients(), c.internalFrom());
    }

    public RelayConfig relayConfig() {
        Effective current = current();
        Config c = current.config();
        try {
            return new RelayConfig(current.revision(), current.updatedAt(),
                    current.repeatNormalSeconds(), current.repeatCriticalSeconds(), c.categories(),
                    c.externalEmailEnabled(), c.externalEmailRecipients(), c.externalSmtpHost(),
                    c.externalSmtpPort(), c.externalSmtpTlsMode(), c.externalSmtpUsername(),
                    effectiveSecret(c.externalSmtpPasswordEnc(), "CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD"),
                    c.externalSmtpFrom(), c.telegramEnabled(), c.telegramChatId(),
                    effectiveSecret(c.telegramBotTokenEnc(), "CORVEX_ALERT_TELEGRAM_BOT_TOKEN"),
                    c.maxEnabled(), c.maxChatId(), c.maxUserId(),
                    effectiveSecret(c.maxBotTokenEnc(), "CORVEX_ALERT_MAX_BOT_TOKEN"));
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private Effective current() {
        try {
            Optional<Stored> stored = repository.find();
            return stored.map(this::fromStored).orElseGet(this::fromEnvironment);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private Effective fromStored(Stored stored) {
        return new Effective(stored.config(), stored.revision(), stored.updatedAt(), "DATABASE",
                stored.config().repeatNormalMinutes() * 60,
                stored.config().repeatCriticalMinutes() * 60);
    }

    private Effective fromEnvironment() {
        int normalSeconds = envDuration("CORVEX_ALERT_REPEAT_INTERVAL", 14_400);
        int criticalSeconds = envDuration("CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL", 1_800);
        Config config = new Config(
                secondsToMinutes(normalSeconds), secondsToMinutes(criticalSeconds), defaultCategories(),
                true, normalize(env("CORVEX_ALERT_RECIPIENTS", "admin@company.local")),
                normalize(env("CORVEX_ALERT_FROM", "corvex-alert@company.local")),
                envBool("CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED", false),
                normalize(env("CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS", "")),
                normalize(env("CORVEX_ALERT_EXTERNAL_SMTP_HOST", "")),
                envInt("CORVEX_ALERT_EXTERNAL_SMTP_PORT", 587),
                normalize(env("CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE", "starttls")).toLowerCase(),
                normalize(env("CORVEX_ALERT_EXTERNAL_SMTP_USERNAME", "")),
                "", // ENV secret remains in Environment and is never copied into the database document.
                normalize(env("CORVEX_ALERT_EXTERNAL_SMTP_FROM", "")),
                envBool("CORVEX_ALERT_TELEGRAM_ENABLED", false),
                normalize(env("CORVEX_ALERT_TELEGRAM_CHAT_ID", "")), "",
                envBool("CORVEX_ALERT_MAX_ENABLED", false),
                normalize(env("CORVEX_ALERT_MAX_CHAT_ID", "")),
                normalize(env("CORVEX_ALERT_MAX_USER_ID", "")), "");
        return new Effective(config, 0, null, "ENVIRONMENT", normalSeconds, criticalSeconds);
    }

    private AlertSettingsView toView(Effective effective) {
        Config c = effective.config();
        return new AlertSettingsView(effective.revision(), effective.updatedAt(), effective.source(),
                c.repeatNormalMinutes(), c.repeatCriticalMinutes(), c.categories(),
                c.internalEmailEnabled(), c.internalRecipients(), c.internalFrom(),
                c.externalEmailEnabled(), c.externalEmailRecipients(), c.externalSmtpHost(),
                c.externalSmtpPort(), c.externalSmtpTlsMode(), c.externalSmtpUsername(),
                secretState(c.externalSmtpPasswordEnc(), "CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD"),
                c.externalSmtpFrom(), c.telegramEnabled(), c.telegramChatId(),
                secretState(c.telegramBotTokenEnc(), "CORVEX_ALERT_TELEGRAM_BOT_TOKEN"),
                c.maxEnabled(), c.maxChatId(), c.maxUserId(),
                secretState(c.maxBotTokenEnc(), "CORVEX_ALERT_MAX_BOT_TOKEN"));
    }

    private SecretState secretState(String encrypted, String envName) {
        if (encrypted != null && !encrypted.isBlank()) return new SecretState(true, "UI");
        if (!normalize(env(envName, "")).isEmpty()) return new SecretState(true, "ENVIRONMENT");
        return new SecretState(false, "NONE");
    }

    private String updatedSecret(String plain, boolean clear, String previous, String label) {
        boolean supplied = plain != null && !plain.isBlank();
        if (clear && supplied) throw ApiException.badRequest(label + ": выберите замену или очистку, не обе операции");
        if (clear) return "";
        if (!supplied) return previous == null ? "" : previous;
        if (plain.length() > 4096 || plain.indexOf('\r') >= 0 || plain.indexOf('\n') >= 0) {
            throw ApiException.unprocessable(label + ": недопустимая длина или перевод строки");
        }
        try {
            return cipher.encrypt(plain);
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private String effectiveSecret(String encrypted, String envName) {
        return encrypted != null && !encrypted.isBlank() ? cipher.decrypt(encrypted) : env(envName, "");
    }

    private void validate(Config c) {
        if (c.repeatNormalMinutes() < 1 || c.repeatNormalMinutes() > MAX_REPEAT_MINUTES
                || c.repeatCriticalMinutes() < 1 || c.repeatCriticalMinutes() > MAX_REPEAT_MINUTES) {
            throw ApiException.unprocessable("Интервал повторов должен быть от 1 до 43200 минут");
        }
        if (c.externalSmtpPort() < 1 || c.externalSmtpPort() > 65_535) {
            throw ApiException.unprocessable("SMTP-порт должен быть от 1 до 65535");
        }
        if (!c.externalSmtpTlsMode().equals("starttls") && !c.externalSmtpTlsMode().equals("ssl")) {
            throw ApiException.unprocessable("Внешний alert SMTP поддерживает только starttls или ssl");
        }
        if (c.internalEmailEnabled()) {
            validateRecipients(c.internalRecipients(), "получатели внутренней почты");
            validateAddress(c.internalFrom(), "отправитель внутренней почты");
        }
        if (c.externalEmailEnabled()) {
            validateRecipients(c.externalEmailRecipients(), "получатели внешней почты");
            validateAddress(c.externalSmtpFrom(), "отправитель внешней почты");
            validateHost(c.externalSmtpHost());
            if (!c.externalSmtpUsername().isEmpty()
                    && effectiveSecret(c.externalSmtpPasswordEnc(), "CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD").isBlank()) {
                throw ApiException.unprocessable("Для SMTP username нужен пароль или сохранённый секрет из .env");
            }
        }
        if (c.telegramEnabled()) {
            if (effectiveSecret(c.telegramBotTokenEnc(), "CORVEX_ALERT_TELEGRAM_BOT_TOKEN").isBlank()) {
                throw ApiException.unprocessable("Для Telegram нужен bot token");
            }
            validateTarget(c.telegramChatId(), "Telegram chat ID");
        }
        if (c.maxEnabled()) {
            if (effectiveSecret(c.maxBotTokenEnc(), "CORVEX_ALERT_MAX_BOT_TOKEN").isBlank()) {
                throw ApiException.unprocessable("Для MAX нужен bot token");
            }
            if (c.maxChatId().isEmpty() == c.maxUserId().isEmpty()) {
                throw ApiException.unprocessable("Для MAX укажите ровно один chat ID или user ID");
            }
            validateTarget(c.maxChatId().isEmpty() ? c.maxUserId() : c.maxChatId(), "MAX chat/user ID");
        }
    }

    private static Map<String, Boolean> normalizedCategories(Map<String, Boolean> categories) {
        if (categories == null || categories.size() != CATEGORY_CODES.size()
                || !categories.keySet().containsAll(CATEGORY_CODES)) {
            throw ApiException.unprocessable("Передайте все пять поддерживаемых категорий алертов");
        }
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String code : CATEGORY_CODES) {
            Boolean enabled = categories.get(code);
            if (enabled == null) throw ApiException.unprocessable("Категория " + code + " должна быть true или false");
            result.put(code, enabled);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Boolean> defaultCategories() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        CATEGORY_CODES.forEach(code -> result.put(code, true));
        return Collections.unmodifiableMap(result);
    }

    private static void validateRecipients(String raw, String label) {
        List<String> recipients = splitRecipients(raw);
        if (recipients.isEmpty()) throw ApiException.unprocessable("Укажите " + label);
        if (recipients.size() > 50) throw ApiException.unprocessable("Не более 50 получателей alert-канала");
        recipients.forEach(recipient -> validateAddress(recipient, label));
    }

    private static void validateAddress(String address, String label) {
        if (address == null || address.length() > 320 || address.isBlank()
                || address.chars().anyMatch(Character::isWhitespace)
                || address.indexOf('<') >= 0 || address.indexOf('>') >= 0
                || address.indexOf('@') <= 0 || address.indexOf('@') != address.lastIndexOf('@')
                || address.endsWith("@")) {
            throw ApiException.unprocessable("Некорректный email в поле: " + label);
        }
    }

    private static void validateHost(String host) {
        if (host == null || host.isBlank() || host.length() > 255
                || host.chars().anyMatch(Character::isWhitespace)
                || host.indexOf('/') >= 0 || host.indexOf('\\') >= 0) {
            throw ApiException.unprocessable("Некорректный внешний SMTP host");
        }
    }

    private static void validateTarget(String target, String label) {
        if (target == null || !target.matches("-?[0-9]{1,32}")) {
            throw ApiException.unprocessable(label + " должен быть целым числом в строковом формате");
        }
    }

    private static List<String> splitRecipients(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private int envDuration(String name, int defaultSeconds) {
        String value = normalize(env(name, ""));
        if (value.isEmpty()) return defaultSeconds;
        if (!value.matches("[0-9]+[smhd]")) {
            throw new IllegalStateException(name + " must be an integer followed by s, m, h, or d");
        }
        long number = Long.parseLong(value.substring(0, value.length() - 1));
        long multiplier = switch (value.charAt(value.length() - 1)) {
            case 's' -> 1;
            case 'm' -> 60;
            case 'h' -> 3600;
            case 'd' -> 86400;
            default -> throw new IllegalStateException("Unsupported duration");
        };
        long seconds = number * multiplier;
        if (seconds < 1 || seconds > MAX_REPEAT_MINUTES * 60L) {
            throw new IllegalStateException(name + " is outside the supported range");
        }
        return (int) seconds;
    }

    private static int secondsToMinutes(int seconds) {
        return Math.max(1, Math.round(seconds / 60.0f));
    }

    private boolean envBool(String name, boolean fallback) {
        String value = normalize(env(name, ""));
        if (value.isEmpty()) return fallback;
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalStateException(name + " must be true or false");
    }

    private int envInt(String name, int fallback) {
        String value = normalize(env(name, ""));
        return value.isEmpty() ? fallback : Integer.parseInt(value);
    }

    private String env(String name, String fallback) {
        return env.getProperty(name, fallback);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
