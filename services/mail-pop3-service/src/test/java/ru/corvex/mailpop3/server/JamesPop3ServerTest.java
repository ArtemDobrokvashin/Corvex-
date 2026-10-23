package ru.corvex.mailpop3.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.corvex.mailpop3.client.Pop3AuthClient;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
