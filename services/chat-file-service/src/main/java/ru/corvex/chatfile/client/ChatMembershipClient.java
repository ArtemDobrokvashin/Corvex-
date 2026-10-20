package ru.corvex.chatfile.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chatfile.security.InternalTokenGuard;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Авторизация файлов не дублирует таблицы чата: актуальное членство, роль, mute и read-only
 * каждый раз спрашиваются у chat-service по internal API с общим секретом Compose-сети.
 */
@Component
public class ChatMembershipClient {

    private static final Logger log = LoggerFactory.getLogger(ChatMembershipClient.class);

    /** Зеркало ChatDtos.MembershipInternalView; роль — строка, чтобы сервисы не делили JAR. */
    public record Membership(
            UUID roomId,
            UUID userId,
            boolean member,
            String role,
            Instant mutedUntil,
            boolean roomReadOnly) {

        public boolean mutedNow() {
            return mutedUntil != null && mutedUntil.isAfter(Instant.now());
        }

        public boolean roomModerator() {
            return "OWNER".equals(role) || "ADMIN".equals(role);
        }
    }

    private final RestClient client;
    private final InternalTokenGuard tokens;
    private final ObjectMapper objectMapper;

    public ChatMembershipClient(
            @Value("${corvex.chat.base-url:${CORVEX_CHAT_SERVICE_URL:http://chat-service:8088}}")
            String baseUrl,
            InternalTokenGuard tokens,
            ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.client = RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/$", ""))
                .requestFactory(factory)
                .build();
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    ChatMembershipClient(RestClient client, InternalTokenGuard tokens, ObjectMapper objectMapper) {
        this.client = client;
        this.tokens = tokens;
        this.objectMapper = objectMapper;
    }

    public Membership membership(UUID roomId, UUID userId) {
        try {
            Membership membership = client.get()
                    .uri("/internal/chat/rooms/{roomId}/members/{userId}", roomId, userId)
                    .header(InternalTokenGuard.HEADER, internalToken())
                    .retrieve()
                    .body(Membership.class);
            if (membership == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "chat-service вернул пустой ответ");
            }
            return membership;
        } catch (RestClientResponseException e) {
            throw mapException(e);
        } catch (RestClientException e) {
            throw badGateway(e);
        }
    }

    public Membership requireMember(UUID roomId, UUID userId) {
        Membership membership = membership(roomId, userId);
        if (!membership.member()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Пользователь не является участником комнаты");
        }
        return membership;
    }

    private String internalToken() {
        try {
            return tokens.tokenOrFail();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private ResponseStatusException badGateway(RestClientException e) {
        log.warn("chat-service membership call failed: {}", e.getMessage());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "chat-service недоступен");
    }

    private ResponseStatusException mapException(RestClientResponseException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return new ResponseStatusException(status, extractDetail(e.getResponseBodyAsString(), status));
    }

    private String extractDetail(String responseBody, HttpStatus fallback) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode detail = node.get("detail");
            if (detail != null && detail.isTextual() && !detail.asText().isBlank()) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // Тело не Problem Details — статус сам несёт достаточно смысла.
        }
        return "chat-service: " + fallback.getReasonPhrase();
    }
}
