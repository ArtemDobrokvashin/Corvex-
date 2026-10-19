package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Submission identity: пароль проверяет auth-service (основной без TOTP или app-password),
 * профиль ящика берётся там же по userId, а права отправки — из admin-service.
 * Успешная аутентификация кэшируется на 5 минут по логину без пароля: SubEtha отдаёт handler-у
 * только identity-строку, а не наш объект пользователя.
 */
@Component
public class SubmissionIdentityClient {

    private static final Logger log = LoggerFactory.getLogger(SubmissionIdentityClient.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final String authBaseUrl;
    private final String adminBaseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ConcurrentMap<String, CachedMailbox> cache = new ConcurrentHashMap<>();

    public SubmissionIdentityClient(
            @Value("${corvex.auth.base-url:${CORVEX_AUTH_SERVICE_URL:http://auth-service:8081}}") String authBaseUrl,
            @Value("${corvex.admin.base-url:${CORVEX_ADMIN_SERVICE_URL:http://admin-service:8090}}") String adminBaseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        this.authBaseUrl = authBaseUrl.replaceAll("/$", "");
        this.adminBaseUrl = adminBaseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record AuthenticatedMailbox(UUID userId, String login, String email, String displayName, Set<String> permissions) {
        public boolean can(String permission) {
            return permissions.contains(permission);
        }
    }

    private record CachedMailbox(AuthenticatedMailbox mailbox, Instant expiresAt) {
    }

    public Optional<AuthenticatedMailbox> authenticate(String loginRaw, String password) {
        String login = loginRaw == null ? "" : loginRaw.trim().toLowerCase(Locale.ROOT);
        try {
            var body = mapper.createObjectNode();
            body.put("username", loginRaw);
            body.put("password", password);
            JsonNode verify = postJson(authBaseUrl + "/internal/verify-password", mapper.writeValueAsString(body));
            if (verify == null || !verify.path("valid").asBoolean(false)) {
                return Optional.empty();
            }
            UUID userId = UUID.fromString(verify.path("userId").asText());
            JsonNode user = getJson(authBaseUrl + "/internal/users/" + userId);
            if (user == null || !"ACTIVE".equalsIgnoreCase(user.path("status").asText(""))) {
                return Optional.empty();
            }
            JsonNode access = getJson(adminBaseUrl + "/internal/users/" + userId + "/access");
            if (access == null) {
                return Optional.empty(); // права неизвестны -> fail closed для submission
            }
            Set<String> permissions = new LinkedHashSet<>();
            access.path("permissions").forEach(p -> permissions.add(p.asText()));
            var mailbox = new AuthenticatedMailbox(
                    userId, login, user.path("email").asText(""), user.path("displayName").asText(""), permissions);
            cache.put(login, new CachedMailbox(mailbox, Instant.now().plus(CACHE_TTL)));
            return Optional.of(mailbox);
        } catch (Exception e) {
            log.warn("Submission authentication backend failed for {}: {}", login, e.getMessage());
            throw new IllegalStateException("Сервис аутентификации временно недоступен", e);
        }
    }

    public Optional<AuthenticatedMailbox> lookupCached(String loginRaw) {
        String login = loginRaw == null ? "" : loginRaw.trim().toLowerCase(Locale.ROOT);
        CachedMailbox cached = cache.get(login);
        if (cached == null || cached.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(cached.mailbox());
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = request(url).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? mapper.readTree(response.body()) : null;
    }

    private JsonNode postJson(String url, String json) throws IOException, InterruptedException {
        HttpRequest request = request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 ? mapper.readTree(response.body()) : null;
    }

    private HttpRequest.Builder request(String url) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException("CORVEX_INTERNAL_API_TOKEN не настроен");
        }
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("X-Corvex-Internal-Token", internalToken);
    }
}
