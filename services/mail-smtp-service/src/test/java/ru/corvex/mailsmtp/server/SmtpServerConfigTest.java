package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.TopicExchange;
import org.subethamail.smtp.AuthenticationHandlerFactory;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory;
import org.subethamail.smtp.auth.LoginFailedException;
import ru.corvex.mailsmtp.RecordingHttpServer;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpServerConfigTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SmtpServerConfig config = new SmtpServerConfig();
    private RecordingHttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new RecordingHttpServer(request -> RecordingHttpServer.Response.json(404, "{}"));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void deniesRelayToForeignDomainsBeforeAuthLookup() {
        InboundMessageHandlerFactory.RecipientPolicy policy = config.recipientPolicy(
                "company.local", "http://localhost:1", "", new ObjectMapper());

        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_RELAY,
                policy.check("victim@partner.example"));
    }

    @Test
    void normalizesAngleBracketAddresses() {
        assertEquals("user@company.local", SmtpServerConfig.normalizeAddress(" <user@company.local> "));
    }

    @Test
    void normalizeAddressHandlesNullDisplayNameAndDanglingBrackets() {
        assertEquals("", SmtpServerConfig.normalizeAddress(null));
        assertEquals("user@company.local", SmtpServerConfig.normalizeAddress(" User <USER@COMPANY.LOCAL> (work)"));
        assertEquals("user@company.local", SmtpServerConfig.normalizeAddress(" <USER@COMPANY.LOCAL"));
        assertEquals("user@company.local", SmtpServerConfig.normalizeAddress("USER@COMPANY.LOCAL>"));
        assertEquals("plain@company.local", SmtpServerConfig.normalizeAddress(" PLAIN@COMPANY.LOCAL "));
    }

    @Test
    void createsDurableMailExchange() {
        TopicExchange exchange = config.corvexMailExchange();

        assertEquals("corvex.mail", exchange.getName());
        assertTrue(exchange.isDurable());
        assertEquals("topic", exchange.getType());
    }

    @Test
    void submissionAuthFactoryMapsIdentityResultsToLoginOutcomes() throws Exception {
        SubmissionIdentityClient identities = mock(SubmissionIdentityClient.class);
        AuthenticationHandlerFactory factory = config.submissionAuthenticationHandlerFactory(identities);
        var validator = ((EasyAuthenticationHandlerFactory) factory).getValidator();
        MessageContext context = mock(MessageContext.class);
        var mailbox = new SubmissionIdentityClient.AuthenticatedMailbox(
                UUID.randomUUID(), "alice", "alice@company.local", "Alice", Set.of("MAIL_SEND_INTERNAL"));

        when(identities.authenticate("alice", "secret")).thenReturn(Optional.of(mailbox));
        assertDoesNotThrow(() -> validator.login("alice", "secret", context));

        when(identities.authenticate("alice", "secret")).thenReturn(Optional.empty());
        LoginFailedException failed = assertThrows(LoginFailedException.class,
                () -> validator.login("alice", "secret", context));
        assertEquals("Authentication failed", failed.getMessage());

        when(identities.authenticate("alice", "secret")).thenThrow(new IllegalStateException("backend down"));
        LoginFailedException unavailable = assertThrows(LoginFailedException.class,
                () -> validator.login("alice", "secret", context));
        assertEquals("Authentication temporarily unavailable", unavailable.getMessage());
    }

    @Test
    void recipientPolicyChecksAuthServiceAndCachesDefinitiveDecisions() {
        server.setHandler(request -> {
            String query = String.valueOf(request.uri().getRawQuery());
            if (query.contains("blocked%40")) {
                return RecordingHttpServer.Response.json(200, "{\"status\":\"BLOCKED\"}");
            }
            if (query.contains("missing%40")) {
                return RecordingHttpServer.Response.json(404, "{}");
            }
            return RecordingHttpServer.Response.json(200, "{\"status\":\"active\"}");
        });
        var policy = config.recipientPolicy(" Company.Local, Branch.Local ",
                server.baseUrl() + "/", "internal-token", mapper);

        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT,
                policy.check(" <USER@COMPANY.LOCAL> "));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT,
                policy.check("user@company.local"));
        assertEquals(1, server.requests().size());
        RecordingHttpServer.Request request = server.requests().get(0);
        assertEquals("/internal/users/by-email", request.path());
        assertEquals("email=user%40company.local", request.uri().getRawQuery());
        assertEquals("internal-token", request.header("X-Corvex-Internal-Token"));

        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT,
                policy.check("user@branch.local"));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER,
                policy.check("blocked@company.local"));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER,
                policy.check("blocked@company.local"));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER,
                policy.check("missing@company.local"));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER,
                policy.check("missing@company.local"));
        assertEquals(4, server.requests().size());
    }

    @Test
    void recipientPolicyEncodesPlusAddressing() {
        server.setHandler(request -> RecordingHttpServer.Response.json(200, "{\"status\":\"ACTIVE\"}"));
        var policy = config.recipientPolicy("company.local", server.baseUrl(), "token", mapper);

        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT,
                policy.check("user+tag@company.local"));
        assertEquals("email=user%2Btag%40company.local", server.requests().get(0).uri().getRawQuery());
    }

    @Test
    void recipientPolicyRejectsMalformedAddressesWithoutHttp() {
        var policy = config.recipientPolicy("company.local", server.baseUrl(), "token", mapper);

        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER, policy.check("nope"));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER, policy.check("@company.local"));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER, policy.check("user@"));
        assertEquals(0, server.requests().size());
    }

    @Test
    void recipientPolicyTempFailsOnMissingTokenHttpErrorsAndConnectionFailure() throws IOException {
        var noToken = config.recipientPolicy("company.local", server.baseUrl(), " ", mapper);
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL,
                noToken.check("user@company.local"));
        assertEquals(0, server.requests().size());

        server.setHandler(request -> RecordingHttpServer.Response.empty(503));
        var flaky = config.recipientPolicy("company.local", server.baseUrl(), "token", mapper);
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL,
                flaky.check("user@company.local"));
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL,
                flaky.check("user@company.local"));
        assertEquals(2, server.requests().size(), "temporary decisions must not be cached");

        int closedPort = closedPort();
        var unreachable = config.recipientPolicy(
                "company.local", "http://127.0.0.1:" + closedPort, "token", mapper);
        assertEquals(InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL,
                unreachable.check("user@company.local"));
    }

    @Test
    void rawMessageStoreClientStoresStreamsAndParsesReceipt() throws Exception {
        server.setHandler(request -> RecordingHttpServer.Response.json(201,
                "{\"rawRef\":\"raw-77\",\"sha256\":\"abc123\",\"sizeBytes\":42}"));
        var store = config.rawMessageStoreClient(server.baseUrl() + "/", "store-token", mapper);
        byte[] eml = "Subject: store\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);

        var stored = store.storeRaw(new ByteArrayInputStream(eml));

        assertEquals("raw-77", stored.rawRef());
        assertEquals("abc123", stored.sha256());
        assertEquals(42L, stored.sizeBytes());
        RecordingHttpServer.Request request = server.requests().get(0);
        assertEquals("POST", request.method());
        assertEquals("/internal/raw", request.path());
        assertEquals("store-token", request.header("X-Corvex-Internal-Token"));
        assertEquals("application/octet-stream", request.header("Content-Type"));
        assertEquals(new String(eml, StandardCharsets.UTF_8), request.bodyText());
    }

    @Test
    void rawMessageStoreClientMaps413AndOtherErrors() {
        var store = config.rawMessageStoreClient(server.baseUrl(), "token", mapper);
        server.setHandler(request -> RecordingHttpServer.Response.empty(413));
        assertThrows(InboundMessageHandlerFactory.RawMessageStoreClient.MessageTooLargeException.class,
                () -> store.storeRaw(new ByteArrayInputStream(new byte[]{1})));

        server.setHandler(request -> RecordingHttpServer.Response.empty(500));
        IOException error = assertThrows(IOException.class,
                () -> store.storeRaw(new ByteArrayInputStream(new byte[]{1})));
        assertTrue(error.getMessage().contains("500"));
    }

    @Test
    void rawMessageStoreClientDeleteIsBestEffortAndRequiresTokenAndRef() throws IOException {
        var store = config.rawMessageStoreClient(server.baseUrl(), "token", mapper);
        server.setHandler(request -> RecordingHttpServer.Response.empty(204));

        store.deleteRaw("raw-88");
        assertEquals(1, server.requests().size());
        assertEquals("DELETE", server.requests().get(0).method());
        assertEquals("/internal/raw/raw-88", server.requests().get(0).path());

        store.deleteRaw(null);
        assertEquals(1, server.requests().size());

        var noToken = config.rawMessageStoreClient(server.baseUrl(), "", mapper);
        assertThrows(IOException.class, () -> noToken.storeRaw(new ByteArrayInputStream(new byte[]{1})));
        noToken.deleteRaw("raw-89");
        assertEquals(1, server.requests().size());

        int closedPort = closedPort();
        var unreachable = config.rawMessageStoreClient("http://127.0.0.1:" + closedPort, "token", mapper);
        assertDoesNotThrow(() -> unreachable.deleteRaw("raw-90"));
    }

    @Test
    void disabledListenerDoesNotBindPorts() throws Exception {
        int[] ports = twoFreePorts();
        int inboundPort = ports[0];
        int submissionPort = ports[1];
        var listener = listener(inboundPort, submissionPort, false,
                tempDir.resolve("missing.pem").toString(), tempDir.resolve("missing-key.pem").toString());

        listener.start();
        try {
            assertPortClosed(inboundPort);
            assertPortClosed(submissionPort);
        } finally {
            listener.stop();
        }
    }

    @Test
    void enabledListenerWithoutTlsStartsOnlyInbound() throws Exception {
        int[] ports = twoFreePorts();
        int inboundPort = ports[0];
        int submissionPort = ports[1];
        var listener = listener(inboundPort, submissionPort, true,
                tempDir.resolve("missing.pem").toString(), tempDir.resolve("missing-key.pem").toString());

        listener.start();
        try {
            assertTrue(readGreeting(inboundPort).startsWith("220"));
            assertPortClosed(submissionPort);
        } finally {
            listener.stop();
        }
        assertPortClosed(inboundPort);
    }

    @Test
    void enabledListenerWithTlsStartsInboundAndSubmission() throws Exception {
        TlsTestMaterial.Material material = TlsTestMaterial.generateRsa(tempDir);
        int[] ports = twoFreePorts();
        int inboundPort = ports[0];
        int submissionPort = ports[1];
        var listener = listener(inboundPort, submissionPort, true,
                material.certificatePem().toString(), material.privateKeyPem().toString());

        listener.start();
        try {
            assertTrue(readGreeting(inboundPort).startsWith("220"));
            assertTrue(readGreeting(submissionPort).startsWith("220"));
        } finally {
            listener.stop();
        }
        assertPortClosed(inboundPort);
        assertPortClosed(submissionPort);
    }

    private SmtpServerConfig.SmtpListener listener(int inboundPort, int submissionPort, boolean enabled,
                                                   String certFile, String keyFile) {
        return new SmtpServerConfig.SmtpListener(
                mock(InboundMessageHandlerFactory.class),
                mock(SubmissionMessageHandlerFactory.class),
                mock(AuthenticationHandlerFactory.class),
                inboundPort,
                submissionPort,
                enabled,
                "mail.company.local",
                certFile,
                keyFile);
    }

    private static String readGreeting(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
            socket.setSoTimeout(2_000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String greeting = reader.readLine();
            return greeting == null ? "" : greeting;
        }
    }

    private static void assertPortClosed(int port) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                Thread.sleep(25);
            } catch (IOException expected) {
                return;
            }
        }
        throw new AssertionError("Port " + port + " is still accepting connections");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static int[] twoFreePorts() throws IOException {
        try (ServerSocket first = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
             ServerSocket second = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            return new int[]{first.getLocalPort(), second.getLocalPort()};
        }
    }

    private static int closedPort() throws IOException {
        return freePort();
    }
}
