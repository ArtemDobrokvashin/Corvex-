package ru.corvex.mailav.pipeline;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawMessageClientTest {

    @Test
    void opensRawMessageStreamWithInternalToken() throws Exception {
        byte[] eml = "Subject: scan\r\n\r\nattachment carrier".getBytes(StandardCharsets.UTF_8);
        try (OneShotHttpServer server = new OneShotHttpServer("200 OK", eml)) {
            RawMessageClient client = new RawMessageClient("http://127.0.0.1:" + server.port() + "/", "secret");

            try (InputStream input = client.open("raw-77")) {
                assertArrayEquals(eml, input.readAllBytes());
            }

            String request = server.request();
            assertTrue(request.startsWith("GET /internal/raw/raw-77 HTTP/1.1"));
            assertTrue(request.contains("X-Corvex-Internal-Token: secret"));
        }
    }

    @Test
    void missingInternalTokenFailsBeforeRequest() {
        IOException blank = assertThrows(IOException.class,
                () -> new RawMessageClient("http://127.0.0.1:1", " ").open("raw-1"));
        IOException missing = assertThrows(IOException.class,
                () -> new RawMessageClient("http://127.0.0.1:1", null).open("raw-1"));

        assertEquals("CORVEX_INTERNAL_API_TOKEN не настроен", blank.getMessage());
        assertEquals("CORVEX_INTERNAL_API_TOKEN не настроен", missing.getMessage());
    }

    @Test
    void nonSuccessfulStorageResponseBecomesIOException() throws Exception {
        try (OneShotHttpServer server = new OneShotHttpServer("503 Service Unavailable", new byte[0])) {
            RawMessageClient client = new RawMessageClient("http://127.0.0.1:" + server.port(), "secret");

            IOException error = assertThrows(IOException.class, () -> client.open("unavailable"));

            assertTrue(error.getMessage().contains("HTTP 503"));
            assertTrue(error.getMessage().contains("unavailable"));
        }
    }

    private static final class OneShotHttpServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<String> request = new AtomicReference<>();
        private final AtomicReference<Exception> failure = new AtomicReference<>();

        private OneShotHttpServer(String statusLine, byte[] body) throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.thread = new Thread(() -> serve(statusLine, body), "one-shot-http");
            this.thread.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        private String request() throws Exception {
            assertTrue(done.await(5, TimeUnit.SECONDS), "HTTP server did not finish");
            if (failure.get() != null) {
                throw failure.get();
            }
            return request.get();
        }

        private void serve(String statusLine, byte[] body) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5_000);
                request.set(readHeaders(socket.getInputStream()));
                String headers = "HTTP/1.1 " + statusLine + "\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (Exception e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int[] terminator = {'\r', '\n', '\r', '\n'};
            int matched = 0;
            int value;
            while ((value = input.read()) != -1) {
                bytes.write(value);
                if (value == terminator[matched]) {
                    matched++;
                    if (matched == terminator.length) {
                        break;
                    }
                } else {
                    matched = value == '\r' ? 1 : 0;
                }
            }
            return bytes.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(5_000);
        }
    }
}
