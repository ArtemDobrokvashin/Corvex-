package ru.corvex.mailpop3.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pop3AuthClientTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void authenticatesPasswordAndLoadsActiveUserEmail() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer(request -> {
            if (request.path().equals("/internal/verify-password")) {
                return FakeHttpServer.Response.json(
                        "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
            }
            if (request.path().equals("/internal/users/" + USER_ID)) {
                return FakeHttpServer.Response.json(
                        "{\"status\":\"ACTIVE\",\"email\":\"ivanov@corp.local\"}");
            }
            return FakeHttpServer.Response.status(404);
        })) {
            Pop3AuthClient client = new Pop3AuthClient(server.baseUrl() + "/", "secret", mapper);

            Optional<Pop3AuthClient.AuthenticatedUser> result =
                    client.authenticate("ivanov", "Correct-Horse-9");

            assertEquals(USER_ID, result.orElseThrow().userId());
            assertEquals("ivanov@corp.local", result.orElseThrow().email());
            assertEquals(2, server.requests().size());
            FakeHttpServer.Request verify = server.requests().get(0);
            assertEquals("POST", verify.method());
            assertEquals("secret", verify.header("X-Corvex-Internal-Token"));
            assertEquals("application/json", verify.header("Content-Type"));
            JsonNode body = mapper.readTree(verify.bodyText());
            assertEquals("ivanov", body.path("username").asText());
            assertEquals("Correct-Horse-9", body.path("password").asText());
            assertEquals("GET", server.requests().get(1).method());
            server.rethrowFailure();
        }
    }

    @Test
    void invalidPasswordAndVerifyHttpErrorYieldEmptyWithoutUserLookup() throws Exception {
        assertAuthenticationEmpty(request -> FakeHttpServer.Response.json("{\"valid\":false}"));
        assertAuthenticationEmpty(request -> FakeHttpServer.Response.status(401));
    }

    @Test
    void inactiveOrMissingUserYieldsEmptyAfterSuccessfulPasswordCheck() throws Exception {
        assertAuthenticationAfterVerify(request -> {
            if (request.path().startsWith("/internal/users/")) {
                return FakeHttpServer.Response.json(
                        "{\"status\":\"DISABLED\",\"email\":\"ivanov@corp.local\"}");
            }
            return validVerify();
        }, 2);
        assertAuthenticationAfterVerify(request -> {
            if (request.path().startsWith("/internal/users/")) {
                return FakeHttpServer.Response.status(404);
            }
            return validVerify();
        }, 2);
    }

    @Test
    void missingInternalTokenBecomesIOException() {
        IOException blank = assertThrows(IOException.class,
                () -> new Pop3AuthClient("http://127.0.0.1:1", " ", mapper)
                        .authenticate("ivanov", "secret"));
        IOException missing = assertThrows(IOException.class,
                () -> new Pop3AuthClient("http://127.0.0.1:1", null, mapper)
                        .authenticate("ivanov", "secret"));

        assertEquals("CORVEX_INTERNAL_API_TOKEN не настроен", blank.getMessage());
        assertEquals("CORVEX_INTERNAL_API_TOKEN не настроен", missing.getMessage());
    }

    @Test
    void unavailableAuthServicePropagatesIOException() throws Exception {
        Pop3AuthClient client = new Pop3AuthClient(
                "http://127.0.0.1:" + closedPort(), "secret", mapper);

        assertThrows(IOException.class, () -> client.authenticate("ivanov", "secret"));
    }

    private void assertAuthenticationEmpty(FakeHttpServer.Handler handler) throws Exception {
        try (FakeHttpServer server = new FakeHttpServer(handler)) {
            Pop3AuthClient client = new Pop3AuthClient(server.baseUrl(), "secret", mapper);

            assertTrue(client.authenticate("ivanov", "wrong").isEmpty());
            assertEquals(1, server.requests().size());
            server.rethrowFailure();
        }
    }

    private void assertAuthenticationAfterVerify(
            FakeHttpServer.Handler handler, int expectedRequests) throws Exception {
        try (FakeHttpServer server = new FakeHttpServer(handler)) {
            Pop3AuthClient client = new Pop3AuthClient(server.baseUrl(), "secret", mapper);

            assertTrue(client.authenticate("ivanov", "secret").isEmpty());
            assertEquals(expectedRequests, server.requests().size());
            server.rethrowFailure();
        }
    }

    private static FakeHttpServer.Response validVerify() {
        return FakeHttpServer.Response.json("{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
