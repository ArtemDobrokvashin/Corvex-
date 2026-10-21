package ru.corvex.mailsmtp.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** Just-in-time получение SMTP/IMAP конфигурации внешнего ящика из mail-storage по internal token. */
@Component
public class ExternalAccountConfigClient {

    private final String baseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public ExternalAccountConfigClient(
            @Value("${corvex.storage.base-url:${CORVEX_STORAGE_BASE_URL:http://mail-storage-service:8085}}") String baseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public record TransportConfig(
            UUID accountId,
            UUID ownerId,
            String emailAddress,
            String displayName,
            String provider,
            String incomingProtocol,
            String incomingHost,
            int incomingPort,
            String incomingSecurity,
            String incomingUsername,
            String secret,
            String smtpHost,
            Integer smtpPort,
            String smtpSecurity,
            String smtpUsername,
            boolean saveSentOnServer,
            String sentFolderName) {

        public String effectiveSmtpUsername() {
            return smtpUsername == null || smtpUsername.isBlank() ? incomingUsername : smtpUsername;
        }
    }

    public static class ConfigException extends IOException {
        private final boolean permanent;

        public ConfigException(String message, boolean permanent) {
            super(message);
            this.permanent = permanent;
        }

        public boolean permanent() {
            return permanent;
        }
    }

    public TransportConfig fetch(UUID accountId) throws IOException {
        if (internalToken == null || internalToken.isBlank()) {
            throw new ConfigException("CORVEX_INTERNAL_API_TOKEN не настроен в mail-smtp-service", false);
        }
        URI uri = URI.create(baseUrl + "/internal/external-mail/accounts/" + accountId + "/transport-config");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("X-Corvex-Internal-Token", internalToken)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfigException("Запрос конфигурации внешнего ящика прерван", false);
        }
        if (response.statusCode() == 404 || response.statusCode() == 409 || response.statusCode() == 400) {
            throw new ConfigException("Внешний ящик недоступен для отправки (HTTP " + response.statusCode() + ")", true);
        }
        if (response.statusCode() != 200) {
            throw new ConfigException("mail-storage вернул HTTP " + response.statusCode() + " для внешнего ящика", false);
        }
        JsonNode body = mapper.readTree(response.body());
        return new TransportConfig(
                UUID.fromString(body.path("accountId").asText()),
                UUID.fromString(body.path("ownerId").asText()),
                body.path("emailAddress").asText(),
                nullableText(body, "displayName"),
                body.path("provider").asText(),
                body.path("incomingProtocol").asText(),
                body.path("incomingHost").asText(),
                body.path("incomingPort").asInt(),
                body.path("incomingSecurity").asText(),
                body.path("incomingUsername").asText(),
                body.path("secret").asText(),
                nullableText(body, "smtpHost"),
                body.path("smtpPort").isNumber() ? body.path("smtpPort").asInt() : null,
                body.path("smtpSecurity").asText(),
                nullableText(body, "smtpUsername"),
                body.path("saveSentOnServer").asBoolean(true),
                nullableText(body, "sentFolderName"));
    }

    private static String nullableText(JsonNode body, String field) {
        JsonNode value = body.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
