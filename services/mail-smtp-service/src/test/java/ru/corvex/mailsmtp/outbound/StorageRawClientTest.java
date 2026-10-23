package ru.corvex.mailsmtp.outbound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.corvex.mailsmtp.RecordingHttpServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageRawClientTest {

    private RecordingHttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new RecordingHttpServer(request -> RecordingHttpServer.Response.bytes(
                200, "raw eml".getBytes(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void downloadsRawBytesWithInternalTokenAndNormalizesBaseUrl() throws IOException {
        StorageRawClient client = new StorageRawClient(server.baseUrl() + "/", "top-secret");

        byte[] body = client.download("raw-42");

        assertArrayEquals("raw eml".getBytes(StandardCharsets.UTF_8), body);
        assertEquals(1, server.requests().size());
        RecordingHttpServer.Request request = server.requests().get(0);
        assertEquals("GET", request.method());
        assertEquals("/internal/raw/raw-42", request.path());
        assertEquals("top-secret", request.header("X-Corvex-Internal-Token"));
    }

    @Test
    void maps404ToRawNotFoundAndOtherStatusesToIOException() {
        StorageRawClient client = new StorageRawClient(server.baseUrl(), "token");
        server.setHandler(request -> RecordingHttpServer.Response.empty(404));

        StorageRawClient.RawNotFoundException missing = assertThrows(
                StorageRawClient.RawNotFoundException.class, () -> client.download("missing"));
        assertTrue(missing.getMessage().contains("missing"));

        server.setHandler(request -> RecordingHttpServer.Response.empty(503));
        IOException unavailable = assertThrows(IOException.class, () -> client.download("later"));
        assertTrue(unavailable.getMessage().contains("503"));
        assertTrue(unavailable.getMessage().contains("later"));
    }

    @Test
    void rejectsMissingTokenBeforeHttpCall() {
        StorageRawClient client = new StorageRawClient(server.baseUrl(), " ");

        assertThrows(IOException.class, () -> client.download("raw-1"));
        assertEquals(0, server.requests().size());
    }

    @Test
    void deleteSendsRequestButSwallowsHttpAndNetworkFailures() throws IOException {
        StorageRawClient client = new StorageRawClient(server.baseUrl(), "token");
        server.setHandler(request -> RecordingHttpServer.Response.empty(500));

        assertDoesNotThrow(() -> client.delete("raw-9"));
        assertEquals(1, server.requests().size());
        assertEquals("DELETE", server.requests().get(0).method());
        assertEquals("/internal/raw/raw-9", server.requests().get(0).path());

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        StorageRawClient unreachable = new StorageRawClient("http://127.0.0.1:" + closedPort, "token");
        assertDoesNotThrow(() -> unreachable.delete("raw-10"));
    }

    @Test
    void deleteWithoutTokenDoesNotCallStorage() {
        StorageRawClient client = new StorageRawClient(server.baseUrl(), null);

        assertDoesNotThrow(() -> client.delete("raw-11"));
        assertEquals(0, server.requests().size());
    }
}
