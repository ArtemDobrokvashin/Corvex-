package ru.corvex.mailpop3.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.corvex.mailpop3.client.Pop3AuthClient;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JamesPop3ServerTest {

    @TempDir
    Path tempDir;

    @Test
    void corvexConfigurationOverridesHelloSoftwareAndGreeting() {
        JamesPop3Server.CorvexPop3Configuration config =
                new JamesPop3Server.CorvexPop3Configuration("mail.corp.local");

        assertEquals("mail.corp.local", config.getHelloName());
        assertEquals("Corvex POP3", config.getSoftwareName());
        assertEquals("Corvex POP3 ready", config.getGreeting());
    }

    @Test
    void buildProtocolAssemblesJamesHandlerChainWithoutStartingListeners() throws Exception {
        JamesPop3Server server = server(false, false, 0, "", "");

        assertNotNull(server.buildProtocol());
        assertFalse(server.isRunning());
    }

    @Test
    void disabledServerDoesNotStartAndStopIsIdempotent() {
        JamesPop3Server server = server(false, false, 0, "", "");

        server.start();
        assertFalse(server.isRunning());
        server.stop();
        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    void enabledServerWithoutAnyListenerStaysStopped() {
        JamesPop3Server server = server(true, false, 0, "", "");

        server.start();

        assertFalse(server.isRunning());
        server.stop();
    }

    @Test
    void invalidKeystoreDoesNotCrashLifecycleStart() throws Exception {
        Path badKeystore = Files.writeString(tempDir.resolve("bad.p12"), "not a PKCS12 keystore");
        JamesPop3Server server = server(true, false, 0, badKeystore.toString(), null);

        server.start();

        assertFalse(server.isRunning());
        server.stop();
    }

    @Test
    void plainListenerBindsAcceptsConnectionAndStops() throws Exception {
        int port = freePort();
        JamesPop3Server server = server(true, true, port, "", "");
        try {
            server.start();
            server.start();

            assertTrue(server.isRunning());
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3_000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String greeting = reader.readLine();
                assertNotNull(greeting);
                assertTrue(greeting.startsWith("+OK"), greeting);
            }
        } finally {
            server.stop();
        }
        assertFalse(server.isRunning());
    }

    @Test
    void plainListenerStreamsRetrBodyAndClosesCleanlyOnQuit() throws Exception {
        int port = freePort();
        UUID userId = UUID.randomUUID();
        UUID inboxId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        byte[] eml = "Subject: RETR integration\r\n\r\n.leading dot\r\nbody\r\n"
                .getBytes(StandardCharsets.UTF_8);

        Pop3AuthClient auth = mock(Pop3AuthClient.class);
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        when(auth.authenticate("petrova@company.local", "secret")).thenReturn(Optional.of(
                new Pop3AuthClient.AuthenticatedUser(userId, "petrova@company.local")));
        when(storage.findInbox(userId)).thenReturn(Optional.of(new ProtocolStorageClient.FolderInfo(
                inboxId, null, "INBOX", "INBOX", 2, 1, 1, 1, 1)));
        when(storage.listMessages(userId, inboxId, 0L, 500)).thenReturn(List.of(
                new ProtocolStorageClient.MessageMeta(messageId, 1, 1, "<retr@company.local>",
                        "admin@company.local", "RETR integration", Instant.EPOCH, eml.length,
                        false, false, false, false, false)));
        when(storage.openRawById(userId, messageId)).thenReturn(new ByteArrayInputStream(eml));

        JamesPop3Server server = new JamesPop3Server(auth, storage, true, true, port, 1995,
                "mail.company.local", "", "", 123);
        try {
            server.start();
            assertTrue(server.isRunning());

            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(3_000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));

                assertTrue(reader.readLine().startsWith("+OK"));
                assertEquals("+OK", command(writer, reader, "USER petrova@company.local"));
                assertTrue(command(writer, reader, "PASS secret").startsWith("+OK Welcome"));
                assertEquals("+OK 1 " + eml.length, command(writer, reader, "STAT"));

                assertEquals("+OK Message follows", command(writer, reader, "RETR 1"));
                assertEquals("Subject: RETR integration", reader.readLine());
                assertEquals("", reader.readLine());
                assertEquals("..leading dot", reader.readLine(), "POP3 обязан выполнять dot-stuffing");
                assertEquals("body", reader.readLine());
                assertEquals(".", reader.readLine(), "RETR завершается строкой с одной точкой");

                assertTrue(command(writer, reader, "QUIT").startsWith("+OK"));
                assertEquals(-1, reader.read(), "После QUIT сервер должен закрыть соединение без NPE");
            }
        } finally {
            server.stop();
        }
    }

    private static String command(BufferedWriter writer, BufferedReader reader, String command) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
        return reader.readLine();
    }

    private JamesPop3Server server(boolean enabled, boolean plainEnabled, int plainPort,
                                   String keystorePath, String keystorePassword) {
        return new JamesPop3Server(
                mock(Pop3AuthClient.class),
                mock(ProtocolStorageClient.class),
                enabled,
                plainEnabled,
                plainPort,
                1995,
                "mail.corp.local",
                keystorePath,
                keystorePassword,
                123);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
