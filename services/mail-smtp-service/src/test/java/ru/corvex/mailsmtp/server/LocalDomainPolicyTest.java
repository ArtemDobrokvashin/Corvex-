package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Только loopback-заглушка mail-storage; сеть и James не используются. */
class LocalDomainPolicyTest {

    private HttpServer server;
    private final AtomicReference<Integer> status = new AtomicReference<>(404);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/mail/domains/accepted", exchange -> {
            hits.incrementAndGet();
            token.set(exchange.getRequestHeaders().getFirst("X-Corvex-Internal-Token"));
            byte[] payload = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/internal/mail/domains/accepted";
    }

    private LocalDomainPolicy policy(String configured) {
        return LocalDomainPolicy.withRegistry(configured, url(), "internal-token", new ObjectMapper());
    }

    @Test
    void configuredDomainIsLocalWithoutCallingStorage() {
        LocalDomainPolicy policy = policy(" Company.Local ");

        assertEquals(LocalDomainPolicy.Kind.LOCAL, policy.classify("company.local"));
        assertEquals(LocalDomainPolicy.Kind.LOCAL, policy.classify("COMPANY.LOCAL"));
        assertEquals(0, hits.get());
    }

    @Test
    void domainConfirmedInRegistryBecomesLocal() {
        status.set(200);
        body.set("{\"domains\":[\"company.local\",\"new.example\"],\"registryLoadedAt\":\"2026-10-19T10:00:00Z\"}");
        LocalDomainPolicy policy = policy("company.local");

        assertEquals(LocalDomainPolicy.Kind.LOCAL, policy.classify("new.example"));
        assertEquals("internal-token", token.get());
        assertTrue(policy.isLocal("user@new.example"));
        assertEquals(LocalDomainPolicy.Kind.FOREIGN, policy.classify("partner.example"));
    }

    @Test
    void unavailableRegistryIsUnknownNotForeign() {
        status.set(503);
        LocalDomainPolicy policy = policy("company.local");

        assertEquals(LocalDomainPolicy.Kind.UNKNOWN, policy.classify("new.example"));
        assertEquals(LocalDomainPolicy.Kind.LOCAL, policy.classify("company.local"),
                "статический домен работает и при недоступном реестре");
        assertFalse(policy.isLocal("user@new.example"));
    }

    @Test
    void missingInternalTokenDisablesRegistryInsteadOfReportingUnknown() {
        LocalDomainPolicy withoutToken =
                LocalDomainPolicy.withRegistry("company.local", url(), " ", new ObjectMapper());

        assertEquals(LocalDomainPolicy.Kind.FOREIGN, withoutToken.classify("other.example"));
        assertEquals(0, hits.get());
    }

    @Test
    void failureIsNotRetriedForEveryRecipient() {
        status.set(500);
        LocalDomainPolicy policy = policy("company.local");

        policy.classify("a@new.example");
        policy.classify("b@new.example");
        policy.classify("c@new.example");

        assertEquals(1, hits.get(), "после ошибки запрос повторяется не чаще TTL");
    }

    @Test
    void emptyDomainIsForeign() {
        LocalDomainPolicy policy = policy("company.local");

        assertEquals(LocalDomainPolicy.Kind.FOREIGN, policy.classify(""));
        assertEquals(LocalDomainPolicy.Kind.FOREIGN, policy.classify(null));
    }

    @Test
    void refreshForcesReload() {
        status.set(200);
        body.set("{\"domains\":[\"first.example\"]}");
        LocalDomainPolicy policy = policy("company.local");
        assertEquals(LocalDomainPolicy.Kind.LOCAL, policy.classify("first.example"));

        body.set("{\"domains\":[\"second.example\"]}");
        policy.refresh();

        assertEquals(LocalDomainPolicy.Kind.LOCAL, policy.classify("second.example"));
        assertEquals(LocalDomainPolicy.Kind.FOREIGN, policy.classify("first.example"));
    }
}
