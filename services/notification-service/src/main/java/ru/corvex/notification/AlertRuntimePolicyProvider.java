package ru.corvex.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Несекретная runtime-policy из admin-service; последняя удачная копия переживает его недоступность. */
@Component
public class AlertRuntimePolicyProvider {

    private static final Logger log = LoggerFactory.getLogger(AlertRuntimePolicyProvider.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(10);
    static final List<String> CATEGORY_CODES = List.of(
            "ServiceDown", "HighHttp5xxRate", "MailQueueBacklog", "DiskSpaceLow", "JvmMemoryPressure");

    public record Policy(
            long revision,
            Instant updatedAt,
            int repeatNormalSeconds,
            int repeatCriticalSeconds,
            Map<String, Boolean> categories,
            boolean internalEmailEnabled,
            String internalRecipients,
            String internalFrom) {
    }

    public record Status(long revision, String source, Instant lastSuccessAt,
                         boolean internalEmailEnabled, String internalRecipients) {
    }

    @FunctionalInterface
    interface PolicyFetcher {
        Policy fetch();
    }

    private final PolicyFetcher fetcher;
    private final Policy fallback;
    private final Clock clock;
    private Policy cached;
    private Instant lastAttempt;
    private Instant lastSuccess;
    private String lastStatus = "ENVIRONMENT";

    public AlertRuntimePolicyProvider(
            @Value("${corvex.admin-service-url:http://admin-service:8090}") String adminUrl,
            @Value("${corvex.internal.api-token:}") String internalToken,
            @Value("${corvex.alert.recipients:admin@company.local}") String recipients,
            @Value("${corvex.alert.from:corvex-alert@company.local}") String from,
            @Value("${corvex.alert.repeat-interval:4h}") String normalRepeat,
            @Value("${corvex.alert.critical-repeat-interval:30m}") String criticalRepeat) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(800);
        factory.setReadTimeout(1_200);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(adminUrl)
                .requestFactory(factory);
        if (internalToken != null && !internalToken.isBlank()) {
            builder.defaultHeader("X-Corvex-Internal-Token", internalToken);
        }
        RestClient client = builder.build();
        this.fetcher = () -> client.get().uri("/internal/alerts/policy").retrieve().body(Policy.class);
        this.fallback = new Policy(0, null, durationSeconds(normalRepeat), durationSeconds(criticalRepeat),
                defaultCategories(), true, recipients, from);
        this.clock = Clock.systemUTC();
    }

    AlertRuntimePolicyProvider(PolicyFetcher fetcher, Policy fallback, Clock clock) {
        this.fetcher = fetcher;
        this.fallback = fallback;
        this.clock = clock;
        validate(fallback);
    }

    public synchronized Policy current() {
        Instant now = clock.instant();
        if (cached != null && lastAttempt != null
                && Duration.between(lastAttempt, now).compareTo(REFRESH_INTERVAL) < 0) {
            return cached;
        }
        lastAttempt = now;
        try {
            Policy fetched = fetcher.fetch();
            if (fetched == null) throw new IllegalStateException("Empty alert policy");
            validate(fetched);
            cached = copy(fetched);
            lastSuccess = now;
            lastStatus = "ADMIN";
        } catch (Exception e) {
            lastStatus = cached == null ? "ENVIRONMENT" : "CACHE";
            log.warn("Alert runtime policy unavailable; using {} ({})", lastStatus, e.getClass().getSimpleName());
        }
        return cached == null ? fallback : cached;
    }

    public synchronized Status status() {
        Policy effective = cached == null ? fallback : cached;
        return new Status(effective.revision(), lastStatus, lastSuccess,
                effective.internalEmailEnabled(), effective.internalRecipients());
    }

    static int durationSeconds(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.matches("[0-9]+[smhd]")) {
            throw new IllegalArgumentException("Alert repeat interval must be an integer followed by s, m, h, or d");
        }
        long number = Long.parseLong(value.substring(0, value.length() - 1));
        long multiplier = switch (value.charAt(value.length() - 1)) {
            case 's' -> 1;
            case 'm' -> 60;
            case 'h' -> 3600;
            case 'd' -> 86400;
            default -> throw new IllegalArgumentException("Unsupported alert repeat interval");
        };
        long result = number * multiplier;
        if (result < 1 || result > 2_592_000) throw new IllegalArgumentException("Alert repeat interval out of range");
        return (int) result;
    }

    static Map<String, Boolean> defaultCategories() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        CATEGORY_CODES.forEach(code -> result.put(code, true));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Policy copy(Policy policy) {
        return new Policy(policy.revision(), policy.updatedAt(), policy.repeatNormalSeconds(),
                policy.repeatCriticalSeconds(), java.util.Collections.unmodifiableMap(new LinkedHashMap<>(policy.categories())),
                policy.internalEmailEnabled(), policy.internalRecipients(), policy.internalFrom());
    }

    private static void validate(Policy policy) {
        if (policy.repeatNormalSeconds() < 1 || policy.repeatNormalSeconds() > 2_592_000
                || policy.repeatCriticalSeconds() < 1 || policy.repeatCriticalSeconds() > 2_592_000
                || policy.categories() == null
                || (policy.internalEmailEnabled()
                && (policy.internalRecipients() == null || policy.internalRecipients().isBlank()
                || policy.internalFrom() == null || policy.internalFrom().isBlank()))) {
            throw new IllegalStateException("Invalid alert runtime policy");
        }
    }

}
