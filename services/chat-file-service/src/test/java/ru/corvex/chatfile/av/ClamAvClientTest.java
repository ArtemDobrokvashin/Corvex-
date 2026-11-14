package ru.corvex.chatfile.av;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClamAvClientTest {

    @Test
    void streamsFileWithInstreamFramingAndParsesCleanReply() throws Exception {
        byte[] payload = "chat attachment".getBytes(StandardCharsets.UTF_8);
        try (FakeClamd clamd = new FakeClamd("stream: OK\0")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(payload));

            assertEquals(ClamAvClient.Result.CLEAN, result.result());
            assertNull(result.signature());
            assertEquals("zINSTREAM", clamd.command());
            assertArrayEquals(payload, clamd.payload());
        }
    }

    @Test
    void parsesInfectedSignature() throws Exception {
        try (FakeClamd clamd = new FakeClamd("stream: Win.Test.EICAR_HDB-1 FOUND\0")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{4, 5, 6}));

            assertEquals(ClamAvClient.Result.INFECTED, result.result());
            assertEquals("Win.Test.EICAR_HDB-1", result.signature());
        }
    }

    @Test
    void unknownReplyAndEmptyFileProduceErrorStatus() throws Exception {
        try (FakeClamd clamd = new FakeClamd("stream: engine ERROR\0")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[0]));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
            assertEquals("stream: engine ERROR", result.signature());
            assertArrayEquals(new byte[0], clamd.payload());
        }
    }

    @Test
    void unavailableClamdProducesErrorInsteadOfThrowing() throws Exception {
        ClamAvClient client = new ClamAvClient("127.0.0.1", closedPort());

        ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

        assertEquals(ClamAvClient.Result.ERROR, result.result());
        assertNotNull(result.signature());
    }

    /** Ответ, действительно заканчивающийся на "OK", но не являющийся точным "stream: OK", не чистый. */
    @Test
    void replyEndingWithOkIsNotAutomaticallyClean() throws Exception {
        try (FakeClamd clamd = new FakeClamd("stream: NOT OK\0")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /** Лидирующий пробел перед "stream: OK" — не точный ответ, ошибка. */
    @Test
    void cleanReplyWithLeadingSpaceIsError() throws Exception {
        try (FakeClamd clamd = new FakeClamd(" stream: OK\0")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /** Пробел между "OK" и NUL — не точный ответ, ошибка. */
    @Test
    void cleanReplyWithTrailingSpaceBeforeNulIsError() throws Exception {
        try (FakeClamd clamd = new FakeClamd("stream: OK \0")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /** Перевод строки перед NUL — не точный ответ, ошибка. */
    @Test
    void cleanReplyWithNewlineBeforeNulIsError() throws Exception {
        try (FakeClamd clamd = new FakeClamd("stream: OK\n\0")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /** Пустой ответ clamd — ошибка, а не CLEAN. */
    @Test
    void emptyReplyIsError() throws Exception {
        try (FakeClamd clamd = new FakeClamd("")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /** Усечённый ответ (соединение закрыто без NUL) — ошибка. */
    @Test
    void truncatedReplyIsError() throws Exception {
        try (FakeClamd clamd = new FakeClamd("stream: O")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /** Ответ без завершающего NUL — ошибка, даже если выглядит как "stream: OK". */
    @Test
    void nonNulTerminatedCleanReplyIsError() throws Exception {
        try (FakeClamd clamd = new FakeClamd("stream: OK")) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /** Слишком длинный ответ не приводит к исчерпанию памяти и классифицируется как ошибка. */
    @Test
    void overlyLongReplyIsError() throws Exception {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 1_024 * 1_024 + 10_000; i++) {
            tooLong.append('A');
        }
        try (FakeClamd clamd = new FakeClamd(tooLong.toString())) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port());

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
        }
    }

    /**
     * Таймаут чтения: clamd принял соединение, но не отвечает. Клиент должен вернуть ERROR
     * гораздо быстрее 60 с — тест использует управляемый таймаут 1500 мс через второй
     * конструктор, production-значение (60 с) не меняется.
     */
    @Test
    void readTimeoutIsError() throws Exception {
        try (HoldClamd clamd = new HoldClamd()) {
            ClamAvClient client = new ClamAvClient("127.0.0.1", clamd.port(), 5_000, 1_500);

            ClamAvClient.ScanResult result = client.scan(new ByteArrayInputStream(new byte[]{1}));

            assertEquals(ClamAvClient.Result.ERROR, result.result());
            assertNotNull(result.signature());
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static final class FakeClamd implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<String> command = new AtomicReference<>();
        private final AtomicReference<byte[]> payload = new AtomicReference<>();
        private final AtomicReference<Exception> failure = new AtomicReference<>();

        private FakeClamd(String reply) throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.thread = new Thread(() -> serve(reply, false), "fake-chatfile-clamd");
            this.thread.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        private String command() throws Exception {
            awaitDone();
            return command.get();
        }

        private byte[] payload() throws Exception {
            awaitDone();
            return payload.get();
        }

        private void awaitDone() throws Exception {
            assertTrue(done.await(5, TimeUnit.SECONDS), "fake clamd did not finish");
            if (failure.get() != null) {
                throw failure.get();
            }
        }

        private void serve(String reply, boolean hold) {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(5_000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                command.set(readNullTerminatedCommand(input));
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                while (true) {
                    int length = input.readInt();
                    if (length == 0) {
                        break;
                    }
                    byte[] chunk = input.readNBytes(length);
                    if (chunk.length != length) {
                        throw new IOException("short INSTREAM chunk");
                    }
                    content.write(chunk);
                }
                payload.set(content.toByteArray());
                if (!hold) {
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                // hold: соединение удерживается открытым, чтение клиента упрется в таймаут
            } catch (Exception e) {
                failure.set(e);
            } finally {
                if (socket != null && !hold) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
                done.countDown();
            }
        }

        private static String readNullTerminatedCommand(DataInputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1 && value != 0) {
                bytes.write(value);
            }
            return bytes.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(5_000);
        }
    }

    /**
     * clamd, который принимает соединение, читает запрос, но никогда не отвечает и не закрывает
     * соединение — имитирует подвисший сканер для проверки таймаута чтения.
     */
    private static final class HoldClamd implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CountDownLatch accepted = new CountDownLatch(1);

        private HoldClamd() throws IOException {
            this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            this.thread = new Thread(this::serve, "hold-chatfile-clamd");
            this.thread.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        private void serve() {
            Socket socket = null;
            try {
                socket = server.accept();
                accepted.countDown();
                socket.setSoTimeout(5_000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                readNullTerminatedCommand(input);
                while (true) {
                    int length = input.readInt();
                    if (length == 0) {
                        break;
                    }
                    input.readNBytes(length);
                }
                // никогда не отвечаем и не закрываем сокет
            } catch (Exception ignored) {
            }
        }

        private static String readNullTerminatedCommand(DataInputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1 && value != 0) {
                bytes.write(value);
            }
            return bytes.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws Exception {
            assertTrue(accepted.await(5, TimeUnit.SECONDS), "hold clamd did not accept");
            server.close();
            thread.join(5_000);
        }
    }
}
