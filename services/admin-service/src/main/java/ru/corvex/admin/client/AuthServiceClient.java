package ru.corvex.admin.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.context.i18n.LocaleContextHolder;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.security.InternalApiTokenFilter;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Клиент внутреннего API auth-service: учётные записи живут там, админка —
 * только оркестратор (пользователь в auth_db + членство в группах admin_db + аудит).
 * Ошибки auth-service (409/404/422 с Problem Details) пробрасываются наружу как есть.
 */
@Component
public class AuthServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceClient.class);

    /** Зеркало AuthDtos.UserView из auth-service. */
    public record RemoteUser(
            UUID id, String username, String email, String displayName,
            String source, String status, String department,
            List<String> groups, List<String> permissions,
            long quotaBytes, long usedBytes,
            boolean mustChangePassword, boolean totpEnabled,
            Instant lastLoginAt, Instant createdAt) {
    }

    public record RemotePage(List<RemoteUser> content, int page, int size, long totalElements, int totalPages) {
    }

    public record RemoteStats(
            long totalUsers, long activeUsers, long blockedUsers, long disabledUsers,
            Long loginsLast24h, Long loginsLast7d, String department) {
    }

    private final RestClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthServiceClient(@Value("${corvex.auth-service-url:http://auth-service:8081}") String baseUrl,
                             @Value("${corvex.internal.api-token:}") String internalApiToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(InternalApiTokenFilter.HEADER, internalApiToken == null ? "" : internalApiToken)
                .requestInterceptor((request, body, execution) -> {
                    var locale = LocaleContextHolder.getLocale();
                    if (locale != null) {
                        request.getHeaders().setAcceptLanguageAsLocales(List.of(locale));
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    public RemoteUser createUser(String username, String email, String displayName, String password,
                                 String department, Long quotaBytes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("email", email);
        body.put("displayName", displayName);
        body.put("password", password);
        body.put("department", department);
        body.put("quotaBytes", quotaBytes);
        return call(() -> client.post().uri("/internal/users").body(body).retrieve().body(RemoteUser.class));
    }

    public RemoteUser getUser(UUID id) {
        return call(() -> client.get().uri("/internal/users/{id}", id).retrieve().body(RemoteUser.class));
    }

    public Optional<RemoteUser> findByUsername(String username) {
        try {
            RemoteUser user = client.get().uri("/internal/users/by-username/{u}", username)
                    .retrieve().body(RemoteUser.class);
            return Optional.ofNullable(user);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw mapException(e);
        } catch (RestClientException e) {
            throw ApiException.badGateway("auth-service недоступен: " + e.getMessage());
        }
    }

    public RemotePage listUsers(String q, String status, String department, int page, int size) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/internal/users")
                .queryParam("page", page).queryParam("size", size);
        if (q != null && !q.isBlank()) b.queryParam("q", q);
        if (status != null && !status.isBlank()) b.queryParam("status", status);
        if (department != null && !department.isBlank()) b.queryParam("department", department);
        String uri = b.build().toUriString();
        return call(() -> client.get().uri(uri).retrieve().body(RemotePage.class));
    }

    public RemoteUser patchUser(UUID id, Map<String, Object> patch) {
        return call(() -> client.method(HttpMethod.PATCH).uri("/internal/users/{id}", id)
                .body(patch).retrieve().body(RemoteUser.class));
    }

    public void blockUser(UUID id) {
        call(() -> {
            client.post().uri("/internal/users/{id}/block", id).retrieve().toBodilessEntity();
            return null;
        });
    }

    public void disableUser(UUID id) {
        call(() -> {
            client.post().uri("/internal/users/{id}/disable", id).retrieve().toBodilessEntity();
            return null;
        });
    }

    public RemoteStats stats(String department) {
        UriComponentsBuilder b = UriComponentsBuilder.fromPath("/internal/stats");
        if (department != null && !department.isBlank()) b.queryParam("department", department);
        String uri = b.build().toUriString();
        return call(() -> client.get().uri(uri).retrieve().body(RemoteStats.class));
    }

    // ---------- error mapping ----------

    private interface Call<T> {
        T run();
    }

    private <T> T call(Call<T> call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            throw mapException(e);
        } catch (RestClientException e) {
            log.warn("auth-service call failed: {}", e.getMessage());
            throw ApiException.badGateway("auth-service недоступен");
        }
    }

    private ApiException mapException(RestClientResponseException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return new ApiException(status, extractDetail(e.getResponseBodyAsString(), status));
    }

    private String extractDetail(String responseBody, HttpStatus fallback) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode detail = node.get("detail");
            if (detail != null && detail.isTextual() && !detail.asText().isBlank()) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // тело не Problem Details — отдадим статус как есть
        }
        return "auth-service: " + fallback.getReasonPhrase();
    }
}
