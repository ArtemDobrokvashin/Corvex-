package ru.corvex.mailpop3.client;

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
import java.util.Optional;
import java.util.UUID;

/**
 * Аутентификация POP3-сессии против auth-service: основной пароль (без TOTP — у POP3 нет
 * второго шага) или app-password, ровно как SMTP Submission. Недоступность auth-service —
 * исключение наверх: James вернёт клиенту временную ошибку, а не «неверный пароль»,
 * чтобы почтовый клиент не сбрасывал сохранённые учётные данные при сбое бэкенда.
 */
@Component
public class Pop3AuthClient {

    private static final Logger log = LoggerFactory.getLogger(Pop3AuthClient.class);

    private final String authBaseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public Pop3AuthClient(
            @Value("${corvex.auth.base-url:${CORVEX_AUTH_SERVICE_URL:http://auth-service:8081}}") String authBaseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        this.authBaseUrl = authBaseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record AuthenticatedUser(UUID userId, String email) {
    }

    /** Empty = неверные учётные данные или пользователь неактивен; IOException = бэкенд недоступен. */
    public Optional<AuthenticatedUser> authenticate(String username, String password) throws IOException {
        try {
            var body = mapper.createObjectNode();
            body.put("username", username);
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
            return Optional.of(new AuthenticatedUser(userId, user.path("email").asText("")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Проверка пароля прервана", e);
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        } catch (IOException e) {
            log.warn("auth-service недоступен при POP3-логине {}: {}", username, e.getMessage());
            throw e;
        }
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
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
