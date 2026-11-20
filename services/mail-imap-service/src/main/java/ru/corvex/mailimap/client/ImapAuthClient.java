package ru.corvex.mailimap.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Аутентификация IMAP LOGIN / SASL PLAIN против auth-service: основной пароль (без TOTP —
 * у IMAP нет второго шага в LOGIN) или app-password при включённой 2FA. Логин нормализуется
 * auth-service (логин или email). Недоступность бэкенда — IOException: движок James вернёт
 * временную ошибку аутентификации, а не «неверный пароль».
 */
@Component
public class ImapAuthClient {

    private static final Logger log = LoggerFactory.getLogger(ImapAuthClient.class);

    private final String authBaseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ImapAuthClient(
            @Value("${corvex.auth.base-url:${CORVEX_AUTH_SERVICE_URL:http://auth-service:8081}}") String authBaseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        this.authBaseUrl = authBaseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public record AuthenticatedUser(UUID userId, String email, String displayName) {
    }

    public Optional<AuthenticatedUser> authenticate(String username, String password) throws IOException {
        try {
            var body = mapper.createObjectNode();
            body.put("username", username);
            body.put("password", password);
            JsonNode verify = postJson(authBaseUrl + "/internal/verify-password", mapper.writeValueAsString(body));
            if (verify == null || !verify.path("valid").asBoolean(false)) {
                // Пароль не подтверждён: это единственный случай, когда IMAP обязан
                // ответить «неверный логин/пароль». Раньше сюда же попадали 401 и 400
                // от auth-service, и сбой инфраструктуры выглядел как опечатка в пароле.
                log.warn("IMAP-вход отклонён: auth-service не подтвердил пароль для {}", username);
                return Optional.empty();
            }
            UUID userId = UUID.fromString(verify.path("userId").asText());
            JsonNode user = getJson(authBaseUrl + "/internal/users/" + userId);
            if (user == null || !"ACTIVE".equalsIgnoreCase(user.path("status").asText(""))) {
                log.warn("IMAP-вход отклонён: учётная запись {} не ACTIVE", username);
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(
                    userId, user.path("email").asText(""), user.path("displayName").asText("")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Проверка пароля прервана", e);
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        } catch (IOException e) {
            log.warn("auth-service недоступен при IMAP-логине {}: {}", username, e.getMessage());
            throw e;
        }
    }

    /**
     * Разрешение username (для IMAP это email) в userId без пароля. Нужно James-сессиям,
     * которые создаются не через LOGIN (system session, quota root resolver).
     */
    public Optional<AuthenticatedUser> findActiveByUsername(String username) throws IOException {
        try {
            String url = authBaseUrl + "/internal/users/by-email?email="
                    + URLEncoder.encode(username, StandardCharsets.UTF_8);
            JsonNode user = getJson(url);
            if (user == null || !"ACTIVE".equalsIgnoreCase(user.path("status").asText(""))) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedUser(
                    UUID.fromString(user.path("id").asText()),
                    user.path("email").asText(username),
                    user.path("displayName").asText("")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Поиск пользователя прерван", e);
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        // 404 — содержательный ответ «такого пользователя нет», остальное — сбой.
        if (response.statusCode() == 404) {
            return null;
        }
        return bodyOrThrow(response, url);
    }

    private JsonNode postJson(String url, String json) throws IOException, InterruptedException {
        HttpRequest request = request(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return bodyOrThrow(response, url);
    }

    /**
     * 200 разбирается как JSON, всё остальное — ошибка, а не «пароль не подошёл».
     *
     * <p>Раньше любой не-200 превращался в {@code null}, и вызывающий код отвечал
     * {@code BadCredentialsException}: James показывал «Invalid login/password» при
     * 401 (несовпадение {@code CORVEX_INTERNAL_API_TOKEN}), 400 (пустое значение не
     * прошло валидацию) и 5xx. Диагностика упиралась в пароль, хотя причина была в
     * инфраструктуре.
     */
    static JsonNode bodyOrThrow(HttpResponse<String> response, String url, ObjectMapper mapper) throws IOException {
        int status = response.statusCode();
        if (status == 200) {
            return mapper.readTree(response.body());
        }
        String hint = switch (status) {
            case 401, 403 -> " — проверьте CORVEX_INTERNAL_API_TOKEN у auth-service и mail-imap-service";
            case 400 -> " — auth-service отклонил тело запроса (пустой логин или пароль?)";
            default -> "";
        };
        String path = url.replaceFirst("^https?://[^/]+", "");
        throw new IOException("auth-service ответил " + status + " на " + path + hint);
    }

    private JsonNode bodyOrThrow(HttpResponse<String> response, String url) throws IOException {
        return bodyOrThrow(response, url, mapper);
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
