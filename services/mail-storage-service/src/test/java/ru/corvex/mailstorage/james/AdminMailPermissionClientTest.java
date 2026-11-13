package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.corvex.mailstorage.security.InternalTokenGuard;
import static org.junit.jupiter.api.Assertions.*;

class AdminMailPermissionClientTest {
    private HttpServer server;
    private AdminMailPermissionClient client;
    private final AtomicReference<String> body = new AtomicReference<>("{\"permissions\":[]}");
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            token.set(exchange.getRequestHeaders().getFirst(InternalTokenGuard.HEADER));
            byte[] reply = body.get().getBytes(StandardCharsets.UTF_8);
            if (status.get() == 302) exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/leak");
            exchange.sendResponseHeaders(status.get(), reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        server.start();
        client = new AdminMailPermissionClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper(), new InternalTokenGuard("test-secret"));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void checksCurrentPermissionAndUsesInternalToken() {
        var owner = UUID.randomUUID();
        body.set("{\"permissions\":[\"MAIL_READ_OWN\"]}");
        assertTrue(client.canReadOwnMail(owner));
        assertEquals("/internal/users/" + owner + "/access", path.get());
        assertEquals("test-secret", token.get());
        body.set("{\"permissions\":[]}");
        assertFalse(client.canReadOwnMail(owner));
    }

    @Test void provisioningUsesCurrentManageUsersPermission() {
        var actor = UUID.randomUUID();
        body.set("{\"permissions\":[\"ADMIN_USERS_MANAGE\"]}");
        assertTrue(client.canManageUsers(actor));
        assertFalse(client.canReadOwnMail(actor));
        body.set("{\"permissions\":[\"MAIL_READ_OWN\"]}");
        assertFalse(client.canManageUsers(actor));
    }

    @Test void invalidJsonShapeAndErrorsFailClosed() {
        for (String value : new String[]{"{}", "{\"permissions\":null}", "{\"permissions\":[1]}", "not-json"}) {
            body.set(value);
            assertThrows(IllegalStateException.class, () -> client.canReadOwnMail(UUID.randomUUID()));
        }
        status.set(503);
        assertThrows(IllegalStateException.class, () -> client.canReadOwnMail(UUID.randomUUID()));
        status.set(302);
        assertThrows(IllegalStateException.class, () -> client.canReadOwnMail(UUID.randomUUID()));
    }

    @Test void refusesUnsafeOriginAndMissingSecret() {
        assertThrows(IllegalArgumentException.class, () -> new AdminMailPermissionClient(
                URI.create("http://user:pass@example.test"), new ObjectMapper(), new InternalTokenGuard("s")));
        var missing = new AdminMailPermissionClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                new ObjectMapper(), new InternalTokenGuard(""));
        assertThrows(IllegalStateException.class, () -> missing.canReadOwnMail(UUID.randomUUID()));
        assertNull(path.get());
    }
}
