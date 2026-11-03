package ru.corvex.admin.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.corvex.admin.client.FakeHttpServer.Request;
import ru.corvex.admin.client.FakeHttpServer.Response;
import ru.corvex.admin.security.InternalApiTokenFilter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailStorageClientTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "internal-secret";

    private FakeHttpServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) server.close();
    }

    @Test
    void usageUsesOneAuthenticatedBatchRequest() throws Exception {
        server = new FakeHttpServer(request -> Response.json(200, """
                [{"ownerId":"%s","usedBytes":3435973837,"messageCount":12}]
                """.formatted(USER_ID)));
        MailStorageClient client = new MailStorageClient(server.baseUrl(), TOKEN);

        assertTrue(client.usedBytes(List.of()).isEmpty());
        Map<UUID, Long> result = client.usedBytes(List.of(USER_ID));

        assertEquals(3_435_973_837L, result.get(USER_ID).longValue());
        assertEquals(1, server.requests().size());
        Request request = server.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/internal/mail/usage", request.target());
        assertEquals(TOKEN, request.header(InternalApiTokenFilter.HEADER));
        assertTrue(request.body().contains("\"ownerIds\":[\"" + USER_ID + "\"]"));
    }

    @Test
    void unavailableStorageProducesUnknownUsageRatherThanZero() throws Exception {
        server = new FakeHttpServer(request -> Response.json(503, "{\"detail\":\"down\"}"));

        Map<UUID, Long> result = new MailStorageClient(server.baseUrl(), TOKEN).usedBytes(List.of(USER_ID));

        assertTrue(result.isEmpty());
    }
}
