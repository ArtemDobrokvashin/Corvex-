package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Принимаемые домены для SMTP: статический список из `CORVEX_MAIL_DOMAINS` плюс
 * домены, подтверждённые в James, из mail-storage (внутренний токен, TTL).
 *
 * <p>Почему это отдельный класс, а не копия CSV: и приём (RCPT), и отправка
 * (submission) должны принимать одно и то же решение. Разъехавшиеся копии
 * списка означали бы, что письмо на собственный домен иногда уходит внешним
 * релеем.
 *
 * <p>Третий ответ — UNKNOWN — обязателен. Недоступность mail-storage не должна
 * превращать собственный домен в чужой (это открыло бы релей) и не должна
 * молча отбрасывать письмо: входящая почта получает временный отказ 451,
 * submission — тоже временный отказ.
 */
public final class LocalDomainPolicy {

    private static final Logger log = LoggerFactory.getLogger(LocalDomainPolicy.class);

    public enum Kind { LOCAL, FOREIGN, UNKNOWN }

    private record Registry(Set<String> domains, Instant loadedAt, Instant attemptedAt) { }

    private final Set<String> configured;
    private final String endpoint;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration ttl;
    private final Duration maxStale;
    private volatile Registry registry;

    LocalDomainPolicy(Set<String> configured, String endpoint, String internalToken, ObjectMapper mapper,
                      Duration ttl, Duration maxStale) {
        this.configured = configured.stream()
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.endpoint = endpoint;
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.ttl = ttl;
        this.maxStale = maxStale;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /** Без реестра: только статический список (используется, когда URL не задан). */
    public static LocalDomainPolicy staticOnly(String csv) {
        return new LocalDomainPolicy(parse(csv), null, null, new ObjectMapper(),
                Duration.ofSeconds(30), Duration.ofSeconds(300));
    }

    public static LocalDomainPolicy withRegistry(String csv, String endpoint, String internalToken,
                                                 ObjectMapper mapper) {
        return new LocalDomainPolicy(parse(csv), endpoint, internalToken, mapper,
                Duration.ofSeconds(30), Duration.ofSeconds(300));
    }

    public static Set<String> parse(String csv) {
        Set<String> domains = new LinkedHashSet<>();
        if (csv == null) {
            return domains;
        }
        for (String part : csv.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                domains.add(value);
            }
        }
        return domains;
    }

    public Set<String> configuredDomains() {
        return configured;
    }

    public Kind classify(String domain) {
        String value = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return Kind.FOREIGN;
        }
        if (configured.contains(value)) {
            return Kind.LOCAL;
        }
        if (endpoint == null || endpoint.isBlank() || internalToken == null || internalToken.isBlank()) {
            // Динамического источника нет вовсе: это не «неизвестно», а отсутствие реестра.
            return Kind.FOREIGN;
        }
        Registry current = current();
        if (current == null) {
            return Kind.UNKNOWN;
        }
        if (current.domains().contains(value)) {
            return Kind.LOCAL;
        }
        if (current.loadedAt() == null || current.loadedAt().plus(maxStale).isBefore(Instant.now())) {
            // Устаревший список не доказывает, что домен чужой.
            return Kind.UNKNOWN;
        }
        return Kind.FOREIGN;
    }

    public boolean isLocal(String email) {
        return classify(domainOf(email)) == Kind.LOCAL;
    }

    /** Принудительное обновление для тестов и диагностики. */
    public void refresh() {
        load(true);
    }

    private Registry current() {
        Registry cached = registry;
        Instant now = Instant.now();
        if (cached != null) {
            boolean fresh = cached.loadedAt() != null && cached.loadedAt().plus(ttl).isAfter(now);
            boolean backingOff = cached.attemptedAt() != null && cached.attemptedAt().plus(ttl).isAfter(now);
            if (fresh || backingOff) {
                return cached;
            }
        }
        return load(false);
    }

    private synchronized Registry load(boolean forced) {
        Instant now = Instant.now();
        Registry cached = registry;
        if (!forced && cached != null) {
            boolean fresh = cached.loadedAt() != null && cached.loadedAt().plus(ttl).isAfter(now);
            boolean backingOff = cached.attemptedAt() != null && cached.attemptedAt().plus(ttl).isAfter(now);
            if (fresh || backingOff) {
                return cached;
            }
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Corvex-Internal-Token", internalToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("mail-storage вернул HTTP " + response.statusCode());
            }
            JsonNode body = mapper.readTree(response.body());
            Set<String> domains = new LinkedHashSet<>();
            for (JsonNode node : body.path("domains")) {
                String value = node.asText("").trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    domains.add(value);
                }
            }
            var loaded = new Registry(Set.copyOf(domains), now, now);
            registry = loaded;
            return loaded;
        } catch (IOException | RuntimeException e) {
            log.warn("Список доменов из mail-storage недоступен: {}", e.getClass().getSimpleName());
            if (cached != null) {
                var attempted = new Registry(cached.domains(), cached.loadedAt(), now);
                registry = attempted;
                return attempted;
            }
            var failed = new Registry(Set.of(), null, now);
            registry = failed;
            return failed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            var failed = new Registry(cached == null ? Set.of() : cached.domains(),
                    cached == null ? null : cached.loadedAt(), now);
            registry = failed;
            return failed;
        }
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1);
    }
}
