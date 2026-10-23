package ru.corvex.mailsmtp.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.corvex.mailsmtp.RecordingHttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalAccountConfigClientTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private RecordingHttpServer server;
    private ExternalAccountConfigClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new RecordingHttpServer(request -> RecordingHttpServer.Response.json(200, fullConfigJson()));
        client = new ExternalAccountConfigClient(server.baseUrl() + "/", "internal-token", mapper);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void fetchesAndParsesFullTransportConfig() throws IOException {
        ExternalAccountConfigClient.TransportConfig config = client.fetch(ACCOUNT_ID);

        assertEquals(ACCOUNT_ID, config.accountId());
        assertEquals(OWNER_ID, config.ownerId());
        assertEquals("old@example.ru", config.emailAddress());
        assertEquals("Old Mailbox", config.displayName());
        assertEquals("CUSTOM", config.provider());
        assertEquals("IMAP", config.incomingProtocol());
        assertEquals("imap.example.ru", config.incomingHost());
        assertEquals(993, config.incomingPort());
        assertEquals("SSL_TLS", config.incomingSecurity());
        assertEquals("incoming-user", config.incomingUsername());
        assertEquals("app-password", config.secret());
        assertEquals("smtp.example.ru", config.smtpHost());
        assertEquals(Integer.valueOf(465), config.smtpPort());
        assertEquals("SSL_TLS", config.smtpSecurity());
        assertEquals("smtp-user", config.smtpUsername());
        assertEquals("smtp-user", config.effectiveSmtpUsername());
        assertFalse(config.saveSentOnServer());
        assertEquals("Sent Items", config.sentFolderName());

        assertEquals(1, server.requests().size());
        RecordingHttpServer.Request request = server.requests().get(0);
        assertEquals("GET", request.method());
        assertEquals("/internal/external-mail/accounts/" + ACCOUNT_ID + "/transport-config", request.path());
        assertEquals("internal-token", request.header("X-Corvex-Internal-Token"));
    }

    @Test
    void parsesNullableFieldsAndFallsBackToIncomingUsername() throws IOException {
        server.setHandler(request -> RecordingHttpServer.Response.json(200, """
                {
                  "accountId":"%s",
                  "ownerId":"%s",
                  "emailAddress":"user@example.com",
                  "provider":"YANDEX",
                  "incomingProtocol":"POP3",
                  "incomingHost":"pop.example.com",
                  "incomingPort":995,
                  "incomingSecurity":"SSL_TLS",
                  "incomingUsername":"same-user",
                  "secret":"secret",
                  "smtpSecurity":"STARTTLS"
                }
                """.formatted(ACCOUNT_ID, OWNER_ID)));

        ExternalAccountConfigClient.TransportConfig config = client.fetch(ACCOUNT_ID);

        assertNull(config.displayName());
        assertNull(config.smtpHost());
        assertNull(config.smtpPort());
        assertNull(config.smtpUsername());
        assertNull(config.sentFolderName());
        assertTrue(config.saveSentOnServer());
        assertEquals("same-user", config.effectiveSmtpUsername());
    }

    @Test
    void blankSmtpUsernameAlsoFallsBack() {
        ExternalAccountConfigClient.TransportConfig config = new ExternalAccountConfigClient.TransportConfig(
                ACCOUNT_ID, OWNER_ID, "user@example.com", null, "CUSTOM", "IMAP", "imap.example.com", 993,
                "SSL_TLS", "incoming", "secret", "smtp.example.com", 587, "STARTTLS", " ", true, null);

        assertEquals("incoming", config.effectiveSmtpUsername());
    }

    @Test
    void permanentStatusesBecomePermanentConfigErrors() {
        for (int status : new int[]{400, 404, 409}) {
            server.setHandler(request -> RecordingHttpServer.Response.empty(status));
            ExternalAccountConfigClient.ConfigException error = assertThrows(
                    ExternalAccountConfigClient.ConfigException.class, () -> client.fetch(ACCOUNT_ID));
            assertTrue(error.permanent(), "status " + status);
            assertTrue(error.getMessage().contains(String.valueOf(status)));
        }
    }

    @Test
    void otherStatusesBecomeTemporaryConfigErrors() {
        server.setHandler(request -> RecordingHttpServer.Response.empty(503));

        ExternalAccountConfigClient.ConfigException error = assertThrows(
                ExternalAccountConfigClient.ConfigException.class, () -> client.fetch(ACCOUNT_ID));

        assertFalse(error.permanent());
        assertTrue(error.getMessage().contains("503"));
    }

    @Test
    void missingTokenFailsTemporarilyBeforeHttpCall() {
        ExternalAccountConfigClient noToken = new ExternalAccountConfigClient(server.baseUrl(), "", mapper);

        ExternalAccountConfigClient.ConfigException error = assertThrows(
                ExternalAccountConfigClient.ConfigException.class, () -> noToken.fetch(ACCOUNT_ID));

        assertFalse(error.permanent());
        assertEquals(0, server.requests().size());
    }

    @Test
    void malformedJsonAndConnectionFailureSurfaceAsIOException() throws IOException {
        server.setHandler(request -> RecordingHttpServer.Response.json(200, "not-json"));
        assertThrows(IOException.class, () -> client.fetch(ACCOUNT_ID));

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ExternalAccountConfigClient unreachable = new ExternalAccountConfigClient(
                "http://127.0.0.1:" + closedPort, "token", mapper);
        assertThrows(IOException.class, () -> unreachable.fetch(ACCOUNT_ID));
    }

    private static String fullConfigJson() {
        return """
                {
                  "accountId":"%s",
                  "ownerId":"%s",
                  "emailAddress":"old@example.ru",
                  "displayName":"Old Mailbox",
                  "provider":"CUSTOM",
                  "incomingProtocol":"IMAP",
                  "incomingHost":"imap.example.ru",
                  "incomingPort":993,
                  "incomingSecurity":"SSL_TLS",
                  "incomingUsername":"incoming-user",
                  "secret":"app-password",
                  "smtpHost":"smtp.example.ru",
                  "smtpPort":465,
                  "smtpSecurity":"SSL_TLS",
                  "smtpUsername":"smtp-user",
                  "saveSentOnServer":false,
                  "sentFolderName":"Sent Items"
                }
                """.formatted(ACCOUNT_ID, OWNER_ID);
    }
}
