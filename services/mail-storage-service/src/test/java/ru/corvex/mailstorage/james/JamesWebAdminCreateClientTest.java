package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Only loopback mock HTTP; does not start James or access real accounts. */
class JamesWebAdminCreateClientTest {
    private static final String ADMIN = "A".repeat(40);
    private static final String ACCOUNT = "B".repeat(40);
    private HttpServer server;
    private final Logger wire = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.wire");
    private final Logger headers = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.headers");
    private Level previousWire, previousHeaders;

    @BeforeEach void disableSensitiveLogs() {
        previousWire = wire.getLevel(); previousHeaders = headers.getLevel();
        wire.setLevel(Level.INFO); headers.setLevel(Level.INFO);
    }

    private String login() { return "cvx-" + UUID.randomUUID().toString().replace("-", "") + "@localhost"; }
    private URI start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
    private JamesWebAdminCreateClient client(URI origin) throws Exception {
        return new JamesWebAdminCreateClient(origin, ADMIN, new ObjectMapper(), SSLContext.getDefault(), true);
    }
    @AfterEach void stop() {
        if (server != null) server.stop(0);
        wire.setLevel(previousWire); headers.setLevel(previousHeaders);
    }

    @Test void createsOnlyGeneratedAccountWithoutForce() throws Exception {
        var path = new String[1]; var header = new String[1]; var body = new String[1]; var method = new String[1];
        URI origin = start(exchange -> {
            method[0] = exchange.getRequestMethod(); path[0] = exchange.getRequestURI().toString();
            header[0] = exchange.getRequestHeaders().getFirst("Password");
            body[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(204, -1); exchange.close();
        });
        String login = login();
        try (var client = client(origin)) { assertTrue(client.create(login, ACCOUNT)); }
        assertEquals("PUT", method[0]); assertEquals("/users/" + login, path[0]);
        assertEquals(ADMIN, header[0]);
        assertEquals(ACCOUNT, new ObjectMapper().readTree(body[0]).path("password").textValue());
    }

    @Test void conflictAndRedirectAreNotSuccessAndRedirectIsNotFollowed() throws Exception {
        var requests = new AtomicInteger();
        URI origin = start(exchange -> {
            int count = requests.incrementAndGet();
            if (count == 1) {
                exchange.sendResponseHeaders(409, -1);
            } else if (exchange.getRequestURI().getPath().equals("/redirected")) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.getResponseHeaders().add("Location", "/redirected");
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        try (var client = client(origin)) {
            assertFalse(client.create(login(), ACCOUNT));
            assertFalse(client.create(login(), ACCOUNT));
        }
        assertEquals(2, requests.get());
    }

    @Test void lostResponseIsNotRetried() throws Exception {
        var requests = new AtomicInteger();
        URI origin = start(exchange -> { requests.incrementAndGet(); exchange.getRequestBody().readAllBytes(); exchange.close(); });
        try (var client = client(origin)) {
            var e = assertThrows(java.io.IOException.class, () -> client.create(login(), ACCOUNT));
            assertFalse(e.getMessage().contains(ADMIN)); assertFalse(e.getMessage().contains(ACCOUNT));
        }
        assertEquals(1, requests.get());
    }

    @Test void rejectsArbitraryLoginAndInsecureOrigin() throws Exception {
        URI origin = start(exchange -> { throw new AssertionError("must not send"); });
        try (var client = client(origin)) { assertThrows(IllegalArgumentException.class, () -> client.create("other@localhost", ACCOUNT)); }
        assertThrows(IllegalArgumentException.class, () -> new JamesWebAdminCreateClient(origin, ADMIN, new ObjectMapper(), SSLContext.getDefault()));
    }
    @Test void debugHeadersOrWirePreventSendingSecrets() throws Exception {
        var requests = new AtomicInteger();
        URI origin = start(exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        try (var client = client(origin)) {
            headers.setLevel(Level.DEBUG);
            assertThrows(java.io.IOException.class, () -> client.create(login(), ACCOUNT));
            headers.setLevel(Level.INFO);
            wire.setLevel(Level.DEBUG);
            assertThrows(java.io.IOException.class, () -> client.create(login(), ACCOUNT));
        }
        assertEquals(0, requests.get());
    }

}
