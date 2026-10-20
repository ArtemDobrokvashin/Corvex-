package ru.corvex.chat.client;

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
import org.springframework.web.util.UriComponentsBuilder;
import ru.corvex.chat.security.InternalTokenGuard;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Минимальный корпоративный каталог поверх internal API auth-service.
 * Chat-service не читает auth_db напрямую; наружу в UI отдаются только публичные поля DirectoryUserView.
 */
@Component
public class AuthDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(AuthDirectoryClient.class);

    /** Зеркало AuthDtos.UserView из auth-service. */
    public record RemoteUser(
            UUID id, String username, String email, String displayName,
            String source, String status, String department,
            List<String> groups, List<String> permissions,
            long quotaBytes, long usedBytes,
            boolean mustChangePassword, boolean totpEnabled,
            Instant lastLoginAt, Instant createdAt) {

        public boolean active() {
            return "ACTIVE".equalsIgnoreCase(status);
        }
    }

    public record RemotePage(List<RemoteUser> content, int page, int size, long totalElements, int totalPages) {
    }

    private final RestClient client;
    private final InternalTokenGuard tokens;
    private final ObjectMapper objectMapper;

    public AuthDirectoryClient(
            @Value("${corvex.auth.base-url:${CORVEX_AUTH_SERVICE_URL:http://auth-service:8081}}") String baseUrl,
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

    public Optional<RemoteUser> getUser(UUID id) {
        try {
            RemoteUser user = client.get()
                    .uri("/internal/users/{id}", id)
                    .header(InternalTokenGuard.HEADER, internalToken())
                    .retrieve()
                    .body(RemoteUser.class);
            return Optional.ofNullable(user);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw mapException(e);
        } catch (RestClientException e) {
            throw badGateway(e);
        }
    }

    public List<RemoteUser> getUsersByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(this::getUser)
                .flatMap(Optional::stream)
                .toList();
    }

    public List<RemoteUser> listActiveUsers(String query, int page, int size) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/internal/users")
                .queryParam("status", "ACTIVE")
                .queryParam("page", Math.max(0, page))
                .queryParam("size", Math.min(200, Math.max(1, size)));
        if (query != null && !query.isBlank()) {
            builder.queryParam("q", query.trim());
        }
        String uri = builder.build().encode().toUriString();
        RemotePage result = call(() -> client.get()
                .uri(uri)
                .header(InternalTokenGuard.HEADER, internalToken())
                .retrieve()
                .body(RemotePage.class));
        if (result == null || result.content() == null) {
            return List.of();
        }
        return result.content().stream().filter(RemoteUser::active).toList();
    }

    private String internalToken() {
        try {
            return tokens.tokenOrFail();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    private interface Call<T> {
        T run();
    }

    private <T> T call(Call<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            throw mapException(e);
        } catch (RestClientException e) {
            throw badGateway(e);
        }
    }

    private ResponseStatusException badGateway(RestClientException e) {
        log.warn("auth-service directory call failed: {}", e.getMessage());
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "auth-service недоступен");
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
        return "auth-service: " + fallback.getReasonPhrase();
    }
}
