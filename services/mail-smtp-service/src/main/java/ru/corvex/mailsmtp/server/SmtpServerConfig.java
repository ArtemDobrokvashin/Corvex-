package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.subethamail.smtp.server.SMTPServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Реализации портов SMTP-приёма и жизненный цикл SubEtha-листенера.
 * Stage 2: RCPT проверяется по auth-service (домен + существующий активный ящик),
 * DATA потоково уходит в durable spool mail-storage-service — больше нет UUID-заглушки.
 */
@Configuration
public class SmtpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(SmtpServerConfig.class);
    private static final Duration RECIPIENT_CACHE_TTL = Duration.ofSeconds(60);

    @Bean
    TopicExchange corvexMailExchange() {
        return new TopicExchange(InboundMessageHandlerFactory.EXCHANGE, true, false);
    }

    @Bean
    InboundMessageHandlerFactory.RecipientPolicy recipientPolicy(
            @Value("${corvex.mail.domains:company.local}") String domainsCsv,
            @Value("${corvex.auth.base-url:${CORVEX_AUTH_SERVICE_URL:http://auth-service:8081}}") String authBaseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        Set<String> domains = Stream.of(domainsCsv.split(","))
                .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        String baseUrl = authBaseUrl.replaceAll("/$", "");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        ConcurrentMap<String, CachedDecision> cache = new ConcurrentHashMap<>();
        log.info("Local mail domains (relay allowed only to existing mailboxes): {}", domains);
        return address -> {
            String email = normalizeAddress(address);
            int at = email.lastIndexOf('@');
            if (at <= 0 || at == email.length() - 1) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER;
            }
            if (!domains.contains(email.substring(at + 1))) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_RELAY;
            }
            CachedDecision cached = cache.get(email);
            if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
                return cached.decision();
            }
            if (internalToken == null || internalToken.isBlank()) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL;
            }
            String url = baseUrl + "/internal/users/by-email?email="
                    + URLEncoder.encode(email, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Corvex-Internal-Token", internalToken)
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 404) {
                    return cache(email, InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER, cache);
                }
                if (response.statusCode() != 200) {
                    return InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL;
                }
                JsonNode body = mapper.readTree(response.body());
                var decision = "ACTIVE".equalsIgnoreCase(body.path("status").asText(""))
                        ? InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT
                        : InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER;
                return cache(email, decision, cache);
            } catch (IOException e) {
                log.warn("auth-service недоступен при проверке RCPT {}: {}", email, e.getMessage());
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL;
            }
        };
    }

    private static InboundMessageHandlerFactory.RecipientPolicy.Decision cache(
            String email,
            InboundMessageHandlerFactory.RecipientPolicy.Decision decision,
            ConcurrentMap<String, CachedDecision> cache) {
        cache.put(email, new CachedDecision(decision, Instant.now().plus(RECIPIENT_CACHE_TTL)));
        return decision;
    }

    private record CachedDecision(InboundMessageHandlerFactory.RecipientPolicy.Decision decision, Instant expiresAt) {
    }

    @Bean
    InboundMessageHandlerFactory.RawMessageStoreClient rawMessageStoreClient(
            @Value("${corvex.storage.base-url:${CORVEX_STORAGE_BASE_URL:http://mail-storage-service:8085}}") String storageBaseUrl,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String internalToken,
            ObjectMapper mapper) {
        String baseUrl = storageBaseUrl.replaceAll("/$", "");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new InboundMessageHandlerFactory.RawMessageStoreClient() {
            @Override
            public StoredRaw storeRaw(InputStream eml) throws IOException {
                if (internalToken == null || internalToken.isBlank()) {
                    throw new IOException("CORVEX_INTERNAL_API_TOKEN не настроен");
                }
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/raw"))
                        .timeout(Duration.ofMinutes(2))
                        .header("X-Corvex-Internal-Token", internalToken)
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> eml))
                        .build();
                try {
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 413) {
                        throw new MessageTooLargeException("mail-storage отклонил письмо по размеру");
                    }
                    if (response.statusCode() != 201) {
                        throw new IOException("mail-storage вернул HTTP " + response.statusCode());
                    }
                    JsonNode body = mapper.readTree(response.body());
                    return new StoredRaw(body.path("rawRef").asText(), body.path("sha256").asText(),
                            body.path("sizeBytes").asLong());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Запись в mail-storage прервана", e);
                }
            }

            @Override
            public void deleteRaw(String rawRef) {
                if (internalToken == null || internalToken.isBlank() || rawRef == null) {
                    return;
                }
                try {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/internal/raw/" + rawRef))
                            .timeout(Duration.ofSeconds(5))
                            .header("X-Corvex-Internal-Token", internalToken)
                            .DELETE()
                            .build();
                    http.send(request, HttpResponse.BodyHandlers.discarding());
                } catch (Exception ignored) {
                    // Осиротевший spool допустим; потерянное принятое письмо — нет.
                }
            }
        };
    }

    static String normalizeAddress(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int lt = s.indexOf('<');
        int gt = s.indexOf('>', lt + 1);
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt);
        }
        return s.replace("<", "").replace(">", "").trim().toLowerCase(Locale.ROOT);
    }

    @Component
    static class SmtpListener {
        private final InboundMessageHandlerFactory factory;
        private final int port;
        private final boolean enabled;
        private final String hostname;
        private SMTPServer server;

        SmtpListener(InboundMessageHandlerFactory factory,
                     @Value("${corvex.smtp.inbound-port:2525}") int port,
                     @Value("${corvex.smtp.enabled:false}") boolean enabled,
                     @Value("${corvex.smtp.hostname:${CORVEX_DOMAIN:mail.company.local}}") String hostname) {
            this.factory = factory;
            this.port = port;
            this.enabled = enabled;
            this.hostname = hostname;
        }

        @PostConstruct
        void start() {
            if (!enabled) {
                log.warn("SMTP listener disabled by corvex.smtp.enabled=false");
                return;
            }
            server = new SMTPServer(factory);
            server.setHostName(hostname);
            server.setPort(port);
            server.start();
            log.info("SMTP inbound listener started on port {} (docker maps 25->>{})", port, port);
        }

        @PreDestroy
        void stop() {
            if (server != null) {
                server.stop();
            }
        }
    }
}
