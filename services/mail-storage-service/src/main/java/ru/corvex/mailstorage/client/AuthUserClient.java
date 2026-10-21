package ru.corvex.mailstorage.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.security.InternalTokenGuard;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Разрешение e-mail/UUID в владельца ящика и поиск сотрудников через internal API auth-service. */
@Component
public class AuthUserClient {

    private final String baseUrl;
    private final InternalTokenGuard tokens;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public AuthUserClient(@Value("${corvex.auth.base-url:${CORVEX_AUTH_SERVICE_URL:http://auth-service:8081}}") String baseUrl,
                          InternalTokenGuard tokens,
                          ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.tokens = tokens;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record MailboxOwner(UUID id, String email, String displayName, String status, long quotaBytes) {
        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }

    /** Минимальный профиль сотрудника для выбора владельца внешнего ящика администратором. */
    public record UserSummary(UUID id, String username, String email, String displayName,
                              String department, String status, long quotaBytes) {
        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }

    public Optional<MailboxOwner> findByEmail(String email) {
        String path = "/internal/users/by-email?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
        return fetch(path, email);
    }

    public Optional<MailboxOwner> findById(UUID id) {
        return fetch("/internal/users/" + id, null);
    }

    public Optional<UserSummary> findUserById(UUID id) {
        return getJson("/internal/users/" + id).map(body -> parseUserSummary(body, null));
    }

    /**
     * Поиск только активных сотрудников для админского выбора владельца. auth-service ищет по
     * username, email, displayName и department; размер страницы ограничен, чтобы UI не тянул
     * весь каталог компании ради одного select.
     */
    public List<UserSummary> searchActiveUsers(String query, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        StringBuilder path = new StringBuilder("/internal/users?status=ACTIVE&page=0&size=")
                .append(safeLimit);
        if (query != null && !query.isBlank()) {
            path.append("&q=").append(URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        }
        JsonNode body = getJson(path.toString())
                .orElseThrow(() -> new IllegalStateException("auth-service не вернул список пользователей"));
        JsonNode content = body.path("content");
        if (!content.isArray()) {
            throw new IllegalStateException("auth-service вернул некорректный список пользователей");
        }
        List<UserSummary> result = new ArrayList<>();
        for (JsonNode item : content) {
            UserSummary user = parseUserSummary(item, null);
            if (user.active()) {
                result.add(user);
            }
        }
        return result;
    }

    private Optional<MailboxOwner> fetch(String path, String fallbackEmail) {
        return getJson(path).map(body -> new MailboxOwner(
                UUID.fromString(body.path("id").asText()),
                body.path("email").asText(fallbackEmail),
                body.path("displayName").asText(""),
                body.path("status").asText(""),
                body.path("quotaBytes").asLong(0)));
    }

    private UserSummary parseUserSummary(JsonNode body, String fallbackEmail) {
        JsonNode department = body.get("department");
        return new UserSummary(
                UUID.fromString(body.path("id").asText()),
                body.path("username").asText(""),
                body.path("email").asText(fallbackEmail),
                body.path("displayName").asText(""),
                department == null || department.isNull() ? null : department.asText(null),
                body.path("status").asText(""),
                body.path("quotaBytes").asLong(0));
    }

    private Optional<JsonNode> getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header(InternalTokenGuard.HEADER, tokens.tokenOrFail())
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("auth-service вернул HTTP " + response.statusCode());
            }
            return Optional.of(mapper.readTree(response.body()));
        } catch (IOException e) {
            throw new IllegalStateException("auth-service недоступен для разрешения пользователя", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос в auth-service прерван", e);
        }
    }
}
