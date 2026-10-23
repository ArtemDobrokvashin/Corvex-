package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.corvex.mailsmtp.RecordingHttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionIdentityClientTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private RecordingHttpServer server;
    private SubmissionIdentityClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new RecordingHttpServer(request -> RecordingHttpServer.Response.json(404, "{}"));
        client = client("internal-token");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void authenticatesLoadsPermissionsAndCachesByNormalizedLogin() throws Exception {
        successfulBackend();

        Optional<SubmissionIdentityClient.AuthenticatedMailbox> result =
                client.authenticate(" Alice@Company.Local ", "secret-password");

        assertTrue(result.isPresent());
        SubmissionIdentityClient.AuthenticatedMailbox mailbox = result.orElseThrow();
        assertEquals(USER_ID, mailbox.userId());
        assertEquals("alice@company.local", mailbox.login());
        assertEquals("alice@company.local", mailbox.email());
        assertEquals("Alice Example", mailbox.displayName());
        assertEquals(Set.of("MAIL_SEND_INTERNAL", "MAIL_BULK_SEND"), mailbox.permissions());
        assertTrue(mailbox.can("MAIL_SEND_INTERNAL"));
        assertFalse(mailbox.can("MAIL_SEND_EXTERNAL"));

        assertEquals(3, server.requests().size());
        RecordingHttpServer.Request verify = server.requests().get(0);
        assertEquals("POST", verify.method());
        assertEquals("/internal/verify-password", verify.path());
        assertEquals("internal-token", verify.header("X-Corvex-Internal-Token"));
        assertEquals("application/json", verify.header("Content-Type"));
        JsonNode credentials = mapper.readTree(verify.body());
        assertEquals(" Alice@Company.Local ", credentials.path("username").asText());
        assertEquals("secret-password", credentials.path("password").asText());
        assertEquals("/internal/users/" + USER_ID, server.requests().get(1).path());
        assertEquals("/internal/users/" + USER_ID + "/access", server.requests().get(2).path());

        assertSame(mailbox, client.lookupCached(" ALICE@COMPANY.LOCAL ").orElseThrow());
        assertTrue(client.lookupCached("unknown@company.local").isEmpty());
        assertTrue(client.lookupCached(null).isEmpty());
    }

    @Test
    void failedVerificationAndNon2xxVerificationReturnEmpty() {
        server.setHandler(request -> RecordingHttpServer.Response.json(200, "{\"valid\":false}"));
        assertTrue(client.authenticate("alice", "wrong").isEmpty());
        assertEquals(1, server.requests().size());

        server.clearRequests();
        server.setHandler(request -> RecordingHttpServer.Response.empty(503));
        assertTrue(client.authenticate("alice", "secret").isEmpty());
        assertEquals(1, server.requests().size());
    }

    @Test
    void missingOrInactiveUserAndUnavailableAccessFailClosed() {
        server.setHandler(request -> {
            if (request.path().equals("/internal/verify-password")) {
                return RecordingHttpServer.Response.json(200, "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
            }
            return RecordingHttpServer.Response.json(404, "{}");
        });
        assertTrue(client.authenticate("alice", "secret").isEmpty());
        assertEquals(2, server.requests().size());

        server.clearRequests();
        server.setHandler(request -> {
            if (request.path().equals("/internal/verify-password")) {
                return RecordingHttpServer.Response.json(200, "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
            }
            if (request.path().endsWith("/access")) {
                return RecordingHttpServer.Response.json(200, "{\"permissions\":[\"MAIL_SEND_INTERNAL\"]}");
            }
            return RecordingHttpServer.Response.json(200, "{\"status\":\"BLOCKED\",\"email\":\"alice@company.local\"}");
        });
        assertTrue(client.authenticate("alice", "secret").isEmpty());
        assertEquals(2, server.requests().size());

        server.clearRequests();
        server.setHandler(request -> {
            if (request.path().equals("/internal/verify-password")) {
                return RecordingHttpServer.Response.json(200, "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
            }
            if (request.path().endsWith("/access")) {
                return RecordingHttpServer.Response.empty(503);
            }
            return RecordingHttpServer.Response.json(200, "{\"status\":\"ACTIVE\",\"email\":\"alice@company.local\"}");
        });
        assertTrue(client.authenticate("alice", "secret").isEmpty());
        assertEquals(3, server.requests().size());
    }

    @Test
    void missingOptionalUserFieldsAndPermissionsUseEmptyDefaults() {
        server.setHandler(request -> {
            if (request.path().equals("/internal/verify-password")) {
                return RecordingHttpServer.Response.json(200, "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
            }
            if (request.path().endsWith("/access")) {
                return RecordingHttpServer.Response.json(200, "{}");
            }
            return RecordingHttpServer.Response.json(200, "{\"status\":\"ACTIVE\"}");
        });

        SubmissionIdentityClient.AuthenticatedMailbox mailbox = client.authenticate("alice", "secret").orElseThrow();

        assertEquals("", mailbox.email());
        assertEquals("", mailbox.displayName());
        assertTrue(mailbox.permissions().isEmpty());
    }

    @Test
    void backendContractAndTransportErrorsBecomeIllegalState() throws IOException {
        server.setHandler(request -> RecordingHttpServer.Response.json(200, "{\"valid\":true,\"userId\":\"not-a-uuid\"}"));
        IllegalStateException contract = assertThrows(IllegalStateException.class,
                () -> client.authenticate("alice", "secret"));
        assertInstanceOf(IllegalArgumentException.class, contract.getCause());

        server.setHandler(request -> RecordingHttpServer.Response.json(200, "broken-json"));
        IllegalStateException json = assertThrows(IllegalStateException.class,
                () -> client.authenticate("alice", "secret"));
        assertInstanceOf(IOException.class, json.getCause());

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SubmissionIdentityClient unreachable = new SubmissionIdentityClient(
                "http://127.0.0.1:" + closedPort, "http://127.0.0.1:" + closedPort, "token", mapper);
        IllegalStateException transport = assertThrows(IllegalStateException.class,
                () -> unreachable.authenticate("alice", "secret"));
        assertInstanceOf(IOException.class, transport.getCause());
    }

    @Test
    void missingTokenBecomesIllegalStateWithTokenCauseAndNoLookup() {
        SubmissionIdentityClient noToken = client(" ");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> noToken.authenticate("alice", "secret"));

        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("CORVEX_INTERNAL_API_TOKEN"));
        assertEquals(0, server.requests().size());
    }

    private SubmissionIdentityClient client(String token) {
        return new SubmissionIdentityClient(server.baseUrl() + "/", server.baseUrl() + "/", token, mapper);
    }

    private void successfulBackend() {
        server.setHandler(request -> {
            if (request.path().equals("/internal/verify-password")) {
                return RecordingHttpServer.Response.json(200, "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
            }
            if (request.path().equals("/internal/users/" + USER_ID + "/access")) {
                return RecordingHttpServer.Response.json(200,
                        "{\"permissions\":[\"MAIL_SEND_INTERNAL\",\"MAIL_BULK_SEND\",\"MAIL_SEND_INTERNAL\"]}");
            }
            if (request.path().equals("/internal/users/" + USER_ID)) {
                return RecordingHttpServer.Response.json(200,
                        "{\"status\":\"active\",\"email\":\"alice@company.local\",\"displayName\":\"Alice Example\"}");
            }
            return RecordingHttpServer.Response.json(404, "{}");
        });
    }
}
