package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JamesJmapReadClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> reply = new AtomicReference<>("{}");
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private HttpServer server;
    private URI origin;
    private JamesJmapReadClient client;

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            if (status.get() == 302) exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/leak");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        client = new JamesJmapReadClient(origin, mapper, true);
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void sessionUsesFixedEndpointAndBearer() throws Exception {
        reply.set("{\"primaryAccounts\":{\"urn:ietf:params:jmap:mail\":\"a\"}}");
        assertEquals("a", client.session("test.token").path("primaryAccounts")
                .path("urn:ietf:params:jmap:mail").asText());
        assertEquals("/jmap/session", requestPath.get());
        assertEquals("Bearer test.token", authorization.get());
    }

    @Test
    void readBuildsSingleReadOnlyMethodAndReturnsResult() throws Exception {
        reply.set("{\"methodResponses\":[[\"Mailbox/get\",{\"list\":[]},\"corvex-read\"]]}");
        ObjectNode args = mapper.createObjectNode().put("accountId", "owner-account");
        assertTrue(client.read("token", "Mailbox/get", args).path("list").isArray());
        assertEquals("/jmap", requestPath.get());
        var sent = mapper.readTree(requestBody.get());
        assertEquals("Mailbox/get", sent.path("methodCalls").get(0).get(0).asText());
        assertEquals("owner-account", sent.path("methodCalls").get(0).get(1).path("accountId").asText());
    }

    @Test
    void rejectsInvalidOriginsAndTokensBeforeNetwork() {
        assertThrows(IllegalArgumentException.class, () -> new JamesJmapReadClient(origin, mapper));
        assertThrows(IllegalArgumentException.class, () -> new JamesJmapReadClient(URI.create("https://user:pass@example.test"), mapper));
        assertThrows(IllegalArgumentException.class, () -> new JamesJmapReadClient(URI.create("https://example.test/other"), mapper));
        assertThrows(IllegalArgumentException.class, () -> client.session("bad\r\ntoken"));
        assertThrows(IllegalArgumentException.class, () -> client.session(""));
        assertNull(requestPath.get());
    }

    @Test
    void rejectsWritesAndMissingAccountBeforeNetwork() {
        ObjectNode args = mapper.createObjectNode().put("accountId", "a");
        assertThrows(IllegalArgumentException.class, () -> client.read("token", "Email/set", args));
        assertThrows(IllegalArgumentException.class, () -> client.read("token", "Email/get", mapper.createObjectNode()));
        assertNull(requestPath.get());
    }

    @Test
    void rejectsRedirectAndDoesNotIncludeTokenInError() {
        status.set(302);
        IOException ex = assertThrows(IOException.class, () -> client.session("private-token"));
        assertTrue(ex.getMessage().contains("302"));
        assertFalse(ex.getMessage().contains("private-token"));
    }

    @Test
    void rejectsJmapMethodErrorsAndMismatchedResponse() {
        ObjectNode args = mapper.createObjectNode().put("accountId", "a");
        reply.set("{\"methodResponses\":[[\"error\",{\"type\":\"accountNotFound\"},\"corvex-read\"]]}");
        assertThrows(IOException.class, () -> client.read("token", "Mailbox/get", args));
        reply.set("{\"methodResponses\":[[\"Mailbox/get\",{},\"wrong\"]]}");
        assertThrows(IOException.class, () -> client.read("token", "Mailbox/get", args));
    }

    @Test
    void rejectsMalformedAndOversizedResponses() {
        reply.set("not-json");
        assertThrows(IOException.class, () -> client.session("token"));
        reply.set("x".repeat(1024 * 1024 + 1));
        assertThrows(IOException.class, () -> client.session("token"));
    }
}
