package ru.corvex.mailstorage.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.security.InternalTokenGuard;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfficePreviewClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> body = new AtomicReference<>("%PDF-1.4");
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> name = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst(InternalTokenGuard.HEADER));
            name.set(exchange.getRequestHeaders().getFirst("X-Office-File-Name"));
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
            byte[] payload = status.get() == 200
                    ? body.get().getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (status.get() == 200) {
                exchange.getResponseHeaders().set("Content-Type", "application/pdf");
            }
            exchange.sendResponseHeaders(status.get(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsTokenAndEncodedFileNameAndReturnsPdf() throws Exception {
        var client = client(true);

        try (InputStream response = client.convert(
                new ByteArrayInputStream("docx-bytes".getBytes(StandardCharsets.UTF_8)),
                9, "Отчёт 2026.docx")) {
            assertArrayEquals("%PDF-1.4".getBytes(StandardCharsets.UTF_8), response.readAllBytes());
        }

        assertEquals("secret", token.get());
        assertEquals("Отчёт 2026.docx", new String(
                Base64.getUrlDecoder().decode(name.get()), StandardCharsets.UTF_8));
    }

    @Test
    void mapsConverterStatusesToCallerStatuses() {
        assertStatus(413, 413);
        assertStatus(415, 422);
        assertStatus(422, 422);
        assertStatus(429, 503);
        assertStatus(503, 503);
        assertStatus(504, 503);
        assertStatus(501, 501);
        assertStatus(401, 502);
    }

    @Test
    void rejectsTooLargeFileBeforeNetwork() {
        var client = client(true);

        var error = assertThrows(ResponseStatusException.class, () -> client.convert(
                new ByteArrayInputStream(new byte[]{1}), 25L * 1024 * 1024 + 1, "report.docx"));
        assertEquals(413, error.getStatusCode().value());
    }

    @Test
    void disabledClientDoesNotCallConverter() {
        var client = client(false);

        var error = assertThrows(ResponseStatusException.class,
                () -> client.convert(new ByteArrayInputStream(new byte[0]), 0, "report.docx"));
        assertEquals(501, error.getStatusCode().value());
    }

    @Test
    void missingInternalTokenFailsClosed() {
        var client = new OfficePreviewClient(true, baseUrl, "5s",
                new InternalTokenGuard(""));

        var error = assertThrows(ResponseStatusException.class,
                () -> client.convert(new ByteArrayInputStream(new byte[0]), 0, "report.docx"));
        assertEquals(503, error.getStatusCode().value());
    }

    private void assertStatus(int converterStatus, int expected) {
        status.set(converterStatus);
        var client = client(true);

        var error = assertThrows(ResponseStatusException.class,
                () -> client.convert(new ByteArrayInputStream(new byte[]{1}), 1, "report.docx"));
        assertEquals(expected, error.getStatusCode().value());
    }

    private OfficePreviewClient client(boolean enabled) {
        return new OfficePreviewClient(enabled, baseUrl + "/", "5s",
                new InternalTokenGuard("secret"));
    }
}
