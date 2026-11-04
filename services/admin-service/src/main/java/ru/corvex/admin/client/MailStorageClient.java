package ru.corvex.admin.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.corvex.admin.security.InternalApiTokenFilter;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Read-only batch usage из mail-storage; отсутствие значения означает «недоступно», а не 0. */
@Component
public class MailStorageClient {

    private static final Logger log = LoggerFactory.getLogger(MailStorageClient.class);

    public record UsageView(UUID ownerId, long usedBytes, long messageCount) {
    }

    private final RestClient client;

    public MailStorageClient(@Value("${corvex.storage-service-url}") String baseUrl,
                             @Value("${corvex.internal.api-token}") String internalApiToken) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader(InternalApiTokenFilter.HEADER, internalApiToken == null ? "" : internalApiToken)
                .build();
    }

    public Map<UUID, Long> usedBytes(List<UUID> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }
        try {
            UsageView[] usage = client.post().uri("/internal/mail/usage")
                    .body(Map.of("ownerIds", ownerIds))
                    .retrieve().body(UsageView[].class);
            if (usage == null) {
                return Map.of();
            }
            return Arrays.stream(usage).collect(Collectors.toMap(
                    UsageView::ownerId, UsageView::usedBytes, (first, ignored) -> first));
        } catch (RestClientException e) {
            log.warn("Mail usage is unavailable for {} mailbox(es): {}", ownerIds.size(), e.getMessage());
            return Map.of();
        }
    }
}
