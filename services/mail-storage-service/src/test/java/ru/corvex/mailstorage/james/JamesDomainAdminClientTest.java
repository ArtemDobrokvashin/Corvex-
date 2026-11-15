package ru.corvex.mailstorage.james;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ru.corvex.mailstorage.james.JamesDomainAdmin.CreateOutcome;
import ru.corvex.mailstorage.james.JamesDomainAdmin.Presence;
import static org.junit.jupiter.api.Assertions.*;

/** Только loopback-заглушка HTTP; ни James, ни настоящий WebAdmin не используются. */
class JamesDomainAdminClientTest {

    private static final String SECRET = "S".repeat(40);
    private HttpServer server;
    private final Logger wire = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.wire");
    private final Logger headers = (Logger) LoggerFactory.getLogger("org.apache.hc.client5.http.headers");
    private Level previousWire;
    private Level previousHeaders;

    @BeforeEach void quietSensitiveLogs() {
        previousWire = wire.getLevel();
        previousHeaders = headers.getLevel();
        wire.setLevel(Level.INFO);
        headers.setLevel(Level.INFO);
    }

    @AfterEach void stop() {
        if (server != null) {
            server.stop(0);
        }
        wire.setLevel(previousWire);
        headers.setLevel(previousHeaders);
    }

    private URI start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private JamesDomainAdminClient client(URI origin) throws Exception {
        return new JamesDomainAdminClient(origin, SECRET, SSLContext.getDefault(), true);
    }

    @Test void createsSingleDomainWithoutQueryAndWithSecretHeader() throws Exception {
        var method = new String[1];
        var path = new String[1];
        var header = new String[1];
        var hits = new AtomicInteger();
        URI origin = start(exchange -> {
            hits.incrementAndGet();
            method[0] = exchange.getRequestMethod();
            path[0] = exchange.getRequestURI().toString();
            header[0] = exchange.getRequestHeaders().getFirst("Password");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try (var client = client(origin)) {
            assertEquals(CreateOutcome.PRESENT, client.create("company.local"));
        }
        assertEquals(1, hits.get());
        assertEquals("PUT", method[0]);
        assertEquals("/domains/company.local", path[0]);
        assertEquals(SECRET, header[0]);
    }

    @Test void rejectedDomainIsNotRetried() throws Exception {
        var hits = new AtomicInteger();
        URI origin = start(exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        try (var client = client(origin)) {
            assertEquals(CreateOutcome.REJECTED, client.create("company.local"));
        }
        assertEquals(1, hits.get());
    }

    @Test void ambiguousStatusIsUncertain() throws Exception {
        for (int code : new int[] {401, 403, 404, 409, 500, 502, 504}) {
            URI origin = start(exchange -> {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
            });
            try (var client = client(origin)) {
                assertEquals(CreateOutcome.UNCERTAIN, client.create("company.local"), "status " + code);
                assertEquals(Presence.UNKNOWN, client.presence("company.local"), "status " + code);
            }
            server.stop(0);
            server = null;
        }
    }

    @Test void presenceUsesGetAndDistinguishesAbsentFromUnknown() throws Exception {
        var method = new String[1];
        URI present = start(exchange -> {
            method[0] = exchange.getRequestMethod();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try (var client = client(present)) {
            assertEquals(Presence.PRESENT, client.presence("company.local"));
        }
        assertEquals("GET", method[0]);
    }

    @Test void absentDomainIsDefinite404() throws Exception {
        URI origin = start(exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        try (var client = client(origin)) {
            assertEquals(Presence.ABSENT, client.presence("company.local"));
        }
    }

    @Test void rejectsNonLoopbackPlainHttpAndInvalidDomains() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new JamesDomainAdminClient(URI.create("http://james-admin:8443"), SECRET,
                        SSLContext.getDefault()));
        assertThrows(IllegalArgumentException.class,
                () -> new JamesDomainAdminClient(URI.create("https://james-admin:8443/other"), SECRET,
                        SSLContext.getDefault()));
        assertThrows(IllegalArgumentException.class,
                () -> new JamesDomainAdminClient(URI.create("https://james-admin:8443"), "short",
                        SSLContext.getDefault()));
        URI origin = start(exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try (var client = client(origin)) {
            assertThrows(IllegalArgumentException.class, () -> client.create("../users/x"));
            assertThrows(IllegalArgumentException.class, () -> client.presence("Company.local?force"));
        }
    }
}
