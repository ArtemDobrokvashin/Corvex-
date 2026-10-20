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
import java.util.Optional;
import java.util.UUID;

/** Разрешение e-mail получателя в владельца ящика через internal API auth-service. */
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

    public Optional<MailboxOwner> findByEmail(String email) {
        String path = "/internal/users/by-email?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
        return fetch(path, email);
    }

    public Optional<MailboxOwner> findById(UUID id) {
        return fetch("/internal/users/" + id, null);
    }

    private Optional<MailboxOwner> fetch(String path, String fallbackEmail) {
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
            JsonNode body = mapper.readTree(response.body());
            return Optional.of(new MailboxOwner(
                    UUID.fromString(body.path("id").asText()),
                    body.path("email").asText(fallbackEmail),
                    body.path("displayName").asText(""),
                    body.path("status").asText(""),
                    body.path("quotaBytes").asLong(0)));
        } catch (IOException e) {
            throw new IllegalStateException("auth-service недоступен для разрешения получателя", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос в auth-service прерван", e);
        }
    }
}
