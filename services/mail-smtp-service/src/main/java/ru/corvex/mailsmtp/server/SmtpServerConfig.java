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
import org.subethamail.smtp.AuthenticationHandlerFactory;
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory;
import org.subethamail.smtp.auth.LoginFailedException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    AuthenticationHandlerFactory submissionAuthenticationHandlerFactory(SubmissionIdentityClient identities) {
        return new EasyAuthenticationHandlerFactory((username, password) -> {
            try {
                if (identities.authenticate(username, password).isEmpty()) {
                    throw new LoginFailedException("Authentication failed");
                }
            } catch (IllegalStateException e) {
                // SMTP AUTH не различает временную недоступность бэкенда отдельным кодом на этом уровне;
                // клиент увидит отказ AUTH, а причина останется в логах сервиса.
                throw new LoginFailedException("Authentication temporarily unavailable");
            }
        });
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
        private final InboundMessageHandlerFactory inboundFactory;
        private final SubmissionMessageHandlerFactory submissionFactory;
        private final AuthenticationHandlerFactory submissionAuthFactory;
        private final int inboundPort;
        private final int submissionPort;
        private final boolean enabled;
        private final String hostname;
        private final String tlsCertChainFile;
        private final String tlsPrivateKeyFile;
        private final List<SMTPServer> servers = new ArrayList<>();

        SmtpListener(InboundMessageHandlerFactory inboundFactory,
                     SubmissionMessageHandlerFactory submissionFactory,
                     AuthenticationHandlerFactory submissionAuthFactory,
                     @Value("${corvex.smtp.inbound-port:2525}") int inboundPort,
                     @Value("${corvex.smtp.submission-port:2587}") int submissionPort,
                     @Value("${corvex.smtp.enabled:false}") boolean enabled,
                     @Value("${corvex.smtp.hostname:${CORVEX_DOMAIN:mail.company.local}}") String hostname,
                     @Value("${corvex.smtp.tls.cert-chain-file:${CORVEX_SMTP_TLS_CERT_FILE:/certs/fullchain.pem}}") String tlsCertChainFile,
                     @Value("${corvex.smtp.tls.private-key-file:${CORVEX_SMTP_TLS_KEY_FILE:/certs/privkey.pem}}") String tlsPrivateKeyFile) {
            this.inboundFactory = inboundFactory;
            this.submissionFactory = submissionFactory;
            this.submissionAuthFactory = submissionAuthFactory;
            this.inboundPort = inboundPort;
            this.submissionPort = submissionPort;
            this.enabled = enabled;
            this.hostname = hostname;
            this.tlsCertChainFile = tlsCertChainFile;
            this.tlsPrivateKeyFile = tlsPrivateKeyFile;
        }

        @PostConstruct
        void start() {
            if (!enabled) {
                log.warn("SMTP listeners disabled by corvex.smtp.enabled=false");
                return;
            }
            Optional<javax.net.ssl.SSLContext> tls = SmtpTlsSupport.buildContext(tlsCertChainFile, tlsPrivateKeyFile);

            SMTPServer inbound = tls
                    .map(ctx -> (SMTPServer) new SmtpTlsSupport.PemTlsSmtpServer(inboundFactory, null, ctx))
                    .orElseGet(() -> new SMTPServer(inboundFactory));
            inbound.setHostName(hostname);
            inbound.setPort(inboundPort);
            inbound.setEnableTLS(tls.isPresent());
            inbound.setRequireTLS(false);
            inbound.start();
            servers.add(inbound);
            log.info("SMTP inbound listener started on port {} (STARTTLS {})", inboundPort, tls.isPresent() ? "enabled" : "unavailable");

            if (tls.isEmpty()) {
                log.warn("SMTP submission listener NOT started: TLS cert/key not found at {} / {} (AUTH без TLS запрещён)",
                        tlsCertChainFile, tlsPrivateKeyFile);
                return;
            }
            SMTPServer submission = new SmtpTlsSupport.PemTlsSmtpServer(submissionFactory, submissionAuthFactory, tls.get());
            submission.setHostName(hostname);
            submission.setPort(submissionPort);
            submission.setEnableTLS(true);
            submission.setRequireTLS(true);
            submission.setRequireAuth(true);
            submission.setAuthenticationHandlerFactory(submissionAuthFactory);
            submission.start();
            servers.add(submission);
            log.info("SMTP submission listener started on port {} (AUTH + required STARTTLS)", submissionPort);
        }

        @PreDestroy
        void stop() {
            servers.forEach(SMTPServer::stop);
            servers.clear();
        }
    }
}
