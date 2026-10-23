package ru.corvex.mailstorage.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.corvex.mailstorage.security.InternalTokenGuard;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthUserClientTest {

    private HttpServer server;
    private String baseUrl;
    private AuthUserClient client;
    private final AtomicReference<Response> response = new AtomicReference<>(new Response(200, "{}"));
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> lastToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastUri.set(exchange.getRequestURI().toString());
            lastToken.set(exchange.getRequestHeaders().getFirst(InternalTokenGuard.HEADER));
            Response current = response.get();
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(current.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new AuthUserClient(baseUrl + "/", new InternalTokenGuard("secret"), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void findsOwnerByEncodedEmailAndUsesFallbackAndDefaults() {
        UUID id = UUID.randomUUID();
        respond(200, "{\"id\":\"" + id + "\"}");

        Optional<AuthUserClient.MailboxOwner> result = client.findByEmail("a+b@company.local");

        assertTrue(result.isPresent());
        assertEquals("a+b@company.local", result.get().email());
        assertEquals("", result.get().displayName());
        assertEquals("", result.get().status());
        assertEquals(0, result.get().quotaBytes());
        assertFalse(result.get().active());
        assertEquals("secret", lastToken.get());
        assertTrue(lastUri.get().contains("email=a%2Bb%40company.local"));
    }

    @Test
    void findsOwnerAndUserSummaryById() {
        UUID id = UUID.randomUUID();
        respond(200, "{\"id\":\"" + id + "\",\"username\":\"ivanov\",\"email\":\"i@company.local\","
                + "\"displayName\":\"Иван\",\"department\":\"Sales\",\"status\":\"active\",\"quotaBytes\":42}");

        AuthUserClient.MailboxOwner owner = client.findById(id).orElseThrow();
        AuthUserClient.UserSummary summary = client.findUserById(id).orElseThrow();

        assertTrue(owner.active());
        assertEquals(42, owner.quotaBytes());
        assertEquals("ivanov", summary.username());
        assertEquals("Sales", summary.department());
        assertTrue(summary.active());
        assertTrue(lastUri.get().endsWith("/internal/users/" + id));
    }

    @Test
    void parsesExplicitNullDepartment() {
        UUID id = UUID.randomUUID();
        respond(200, "{\"id\":\"" + id + "\",\"username\":\"u\",\"email\":\"u@x\","
                + "\"displayName\":\"U\",\"department\":null,\"status\":\"ACTIVE\"}");

        assertNull(client.findUserById(id).orElseThrow().department());
    }

    @Test
    void searchClampsLimitEncodesQueryAndFiltersInactiveUsers() {
        UUID active = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        respond(200, "{\"content\":["
                + "{\"id\":\"" + active + "\",\"username\":\"a\",\"email\":\"a@x\",\"displayName\":\"A\",\"status\":\"ACTIVE\"},"
                + "{\"id\":\"" + blocked + "\",\"username\":\"b\",\"email\":\"b@x\",\"displayName\":\"B\",\"status\":\"BLOCKED\"}]}");

        var result = client.searchActiveUsers(" Иван Петров ", 999);

        assertEquals(1, result.size());
        assertEquals(active, result.get(0).id());
        assertTrue(lastUri.get().contains("size=50"));
        assertTrue(lastUri.get().contains("q=%D0%98%D0%B2%D0%B0%D0%BD+%D0%9F%D0%B5%D1%82%D1%80%D0%BE%D0%B2"));

        client.searchActiveUsers(" ", -10);
        assertTrue(lastUri.get().contains("size=1"));
        assertFalse(lastUri.get().contains("&q="));
    }

    @Test
    void mapsNotFoundAndHttpErrors() {
        respond(404, "{\"error\":\"missing\"}");
        assertTrue(client.findById(UUID.randomUUID()).isEmpty());
        assertThrows(IllegalStateException.class, () -> client.searchActiveUsers("", 5));

        respond(503, "down");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> client.findByEmail("a@company.local"));
        assertTrue(error.getMessage().contains("HTTP 503"));
    }

    @Test
    void malformedJsonAndInvalidSearchShapeFailClearly() {
        respond(200, "{bad-json");
        IllegalStateException io = assertThrows(IllegalStateException.class,
                () -> client.findByEmail("a@company.local"));
        assertTrue(io.getMessage().contains("недоступен"));

        respond(200, "{\"content\":{}}");
        IllegalStateException shape = assertThrows(IllegalStateException.class,
                () -> client.searchActiveUsers(null, 5));
        assertTrue(shape.getMessage().contains("некорректный список"));
    }

    @Test
    void invalidUuidInResponsePropagatesAsDataError() {
        respond(200, "{\"id\":\"not-uuid\"}");
        assertThrows(IllegalArgumentException.class, () -> client.findByEmail("a@company.local"));
    }

    @Test
    void blankTokenAndConnectionFailureFailClosed() {
        AuthUserClient noToken = new AuthUserClient(baseUrl, new InternalTokenGuard(" "), new ObjectMapper());
        assertThrows(IllegalStateException.class, () -> noToken.findById(UUID.randomUUID()));

        AuthUserClient unreachable = new AuthUserClient(
                "http://127.0.0.1:1", new InternalTokenGuard("secret"), new ObjectMapper());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> unreachable.findById(UUID.randomUUID()));
        assertTrue(error.getMessage().contains("недоступен"));
    }

    @Test
    void recordActiveChecksAreCaseInsensitiveAndNullSafe() {
        assertTrue(new AuthUserClient.MailboxOwner(UUID.randomUUID(), "a@x", "A", "aCtIvE", 0).active());
        assertFalse(new AuthUserClient.MailboxOwner(UUID.randomUUID(), "a@x", "A", null, 0).active());
        assertFalse(new AuthUserClient.UserSummary(UUID.randomUUID(), "u", "a@x", "A", null, null, 0).active());
    }

    private void respond(int status, String body) {
        response.set(new Response(status, body));
    }

    private record Response(int status, String body) {
    }
}
