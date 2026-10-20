package ru.corvex.auth.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.corvex.auth.security.InternalApiTokenFilter;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Эффективные группы и права пользователя из admin-service (внутренний эндпоинт,
 * через gateway не публикуется; дополнительно требуется X-Corvex-Internal-Token).
 * При недоступности admin-service возвращает Optional.empty(): вызывающие места
 * деградируют безопасно — вход с пустым набором прав (fail-safe deny) либо claims
 * уже выданного токена для /me. Права подтянутся при следующем refresh.
 */
@Component
public class AdminAccessClient {

    private static final Logger log = LoggerFactory.getLogger(AdminAccessClient.class);

    public record AccessInfo(List<String> groups, List<String> permissions) {
        public static AccessInfo empty() {
            return new AccessInfo(List.of(), List.of());
        }
    }

    private final RestClient client;

    public AdminAccessClient(@Value("${corvex.admin-service-url:http://admin-service:8090}") String baseUrl,
                             @Value("${corvex.internal.api-token:}") String internalApiToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(InternalApiTokenFilter.HEADER, internalApiToken == null ? "" : internalApiToken)
                .build();
    }

    public Optional<AccessInfo> fetch(UUID userId) {
        try {
            AccessInfo body = client.get()
                    .uri("/internal/users/{id}/access", userId)
                    .retrieve()
                    .body(AccessInfo.class);
            return Optional.ofNullable(body);
        } catch (Exception e) {
            log.warn("admin-service access lookup failed for user {}: {} (degrading)", userId, e.getMessage());
            return Optional.empty();
        }
    }
}
