package ru.corvex.mailspam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpamAssassinClientTest {

    @Test
    void parsesSpamVerdictAndSendsSpamcCheckRequest() throws Exception {
        byte[] eml = "Subject: hello\r\n\r\nbody".getBytes(StandardCharsets.US_ASCII);
        try (FakeSpamd spamd = new FakeSpamd("SPAMD/1.1 0 EX_OK\r\nSpam: True ; 12.5 / 5.0\r\n")) {
            SpamAssassinClient client = new SpamAssassinClient("127.0.0.1", spamd.port());

            SpamAssassinClient.Verdict verdict = client.check(eml);

            assertTrue(verdict.checked());
            assertTrue(verdict.spam());
            assertEquals(12.5, verdict.score(), 0.001);
            assertEquals(5.0, verdict.threshold(), 0.001);
            String request = spamd.request();
            assertTrue(request.startsWith("CHECK SPAMC/1.5\r\n"));
            assertTrue(request.contains("Content-length: " + eml.length + "\r\n"));
            assertTrue(request.contains("User: corvex\r\n"));
            assertTrue(request.endsWith("Subject: hello\r\n\r\nbody"));
        }
    }

    @Test
    void parsesNegativeSpamScoreFromFalseVerdict() throws Exception {
        try (FakeSpamd spamd = new FakeSpamd("Spam: False ; -1.25 / 5.0\r\n")) {
            SpamAssassinClient client = new SpamAssassinClient("127.0.0.1", spamd.port());

            SpamAssassinClient.Verdict verdict = client.check(new byte[]{1, 2, 3});

            assertTrue(verdict.checked());
            assertFalse(verdict.spam());
            assertEquals(-1.25, verdict.score(), 0.001);
            assertEquals(5.0, verdict.threshold(), 0.001);
        }
    }

    @Test
    void responseWithoutSpamHeaderIsUnchecked() throws Exception {
        try (FakeSpamd spamd = new FakeSpamd("SPAMD/1.1 0 EX_OK\r\n\r\n")) {
            SpamAssassinClient client = new SpamAssassinClient("127.0.0.1", spamd.port());

            SpamAssassinClient.Verdict verdict = client.check(new byte[]{42});

            assertFalse(verdict.checked());
            assertFalse(verdict.spam());
            assertEquals(0, verdict.score(), 0.001);
            assertEquals(5.0, verdict.threshold(), 0.001);
        }
    }

    @Test
    void unavailableSpamdFailsOpenWithUncheckedVerdict() throws Exception {
        SpamAssassinClient client = new SpamAssassinClient("127.0.0.1", closedPort());

        SpamAssassinClient.Verdict verdict = client.check(new byte[]{1});

        assertFalse(verdict.checked());
        assertFalse(verdict.spam());
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static final class FakeSpamd implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<String> request = new AtomicReference<>();
        private final AtomicReference<Exception> failure = new AtomicReference<>();

        private FakeSpamd(String response) throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.thread = new Thread(() -> serve(response), "fake-spamd");
            this.thread.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        private String request() throws Exception {
            assertTrue(done.await(5, TimeUnit.SECONDS), "fake spamd did not finish");
            if (failure.get() != null) {
                throw failure.get();
            }
            return request.get();
        }

        private void serve(String response) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5_000);
                request.set(new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII));
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            } catch (Exception e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(5_000);
        }
    }
}
