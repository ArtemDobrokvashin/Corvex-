package ru.corvex.auth.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.corvex.auth.client.AdminAccessClient.AccessInfo;
import ru.corvex.auth.client.FakeHttpServer.Response;
import ru.corvex.auth.security.InternalApiTokenFilter;

import java.net.ServerSocket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAccessClientTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "internal-secret";

    private FakeHttpServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void fetchReturnsGroupsAndPermissionsAndSendsInternalToken() throws Exception {
        server = new FakeHttpServer(request -> Response.json(200, """
                {"groups":["USER","MANAGER"],"permissions":["MAIL_READ_OWN","ANALYTICS_VIEW_OWN_UNIT"]}
                """));

        Optional<AccessInfo> result = client().fetch(USER_ID);

        assertEquals(Optional.of(new AccessInfo(
                List.of("USER", "MANAGER"),
                List.of("MAIL_READ_OWN", "ANALYTICS_VIEW_OWN_UNIT"))), result);
        assertEquals("GET", server.lastRequest().method());
        assertEquals("/internal/users/" + USER_ID + "/access", server.lastRequest().target());
        assertEquals(TOKEN, server.lastRequest().header(InternalApiTokenFilter.HEADER));
    }

    @Test
    void emptyResponseBodyBecomesEmptyOptional() throws Exception {
        server = new FakeHttpServer(request -> new Response(204, "text/plain", ""));

        assertTrue(client().fetch(USER_ID).isEmpty());
    }

    @Test
    void httpErrorsAndMalformedJsonDegradeToEmptyOptional() throws Exception {
        server = new FakeHttpServer(request -> request.target().contains("11111111")
                ? Response.json(503, "{\"detail\":\"admin unavailable\"}")
                : Response.json(200, "not-json"));

        assertTrue(client().fetch(USER_ID).isEmpty());
        assertTrue(client().fetch(UUID.fromString("22222222-2222-4222-8222-222222222222")).isEmpty());
    }

    @Test
    void transportFailureDegradesToEmptyOptional() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AdminAccessClient client = new AdminAccessClient("http://127.0.0.1:" + closedPort, TOKEN);

        assertTrue(client.fetch(USER_ID).isEmpty());
    }

    @Test
    void emptyAccessInfoFactoryReturnsImmutableEmptyLists() {
        AccessInfo empty = AccessInfo.empty();

        assertEquals(List.of(), empty.groups());
        assertEquals(List.of(), empty.permissions());
    }

    @Test
    void nullConfiguredTokenStillAllowsClientConstructionAndFetchAttempt() throws Exception {
        server = new FakeHttpServer(request -> Response.json(200,
                "{\"groups\":[],\"permissions\":[]}"));
        AdminAccessClient client = new AdminAccessClient(server.baseUrl(), null);

        assertEquals(Optional.of(AccessInfo.empty()), client.fetch(USER_ID));
    }

    private AdminAccessClient client() {
        return new AdminAccessClient(server.baseUrl(), TOKEN);
    }
}
