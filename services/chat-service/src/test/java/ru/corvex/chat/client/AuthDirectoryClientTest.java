package ru.corvex.chat.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import ru.corvex.chat.security.InternalTokenGuard;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthDirectoryClientTest {

    private static final UUID FIRST_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final String TOKEN = "internal-secret";

    private HttpServer server;
    private AuthDirectoryClient client;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<URI> requestUris = new CopyOnWriteArrayList<>();
    private final List<String> requestTokens = new CopyOnWriteArrayList<>();
    private volatile Function<URI, Response> responder = uri -> new Response(404, "{}");

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/users", exchange -> {
            requests.incrementAndGet();
            requestUris.add(exchange.getRequestURI());
            requestTokens.add(exchange.getRequestHeaders().getFirst(InternalTokenGuard.HEADER));
            Response response = responder.apply(exchange.getRequestURI());
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new AuthDirectoryClient(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                new InternalTokenGuard(TOKEN), new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getUserSendsInternalTokenAndParsesRemoteUser() {
        responder = uri -> new Response(200, userJson(FIRST_ID, "active"));

        Optional<AuthDirectoryClient.RemoteUser> result = client.getUser(FIRST_ID);

        assertTrue(result.isPresent());
        assertEquals(FIRST_ID, result.orElseThrow().id());
        assertTrue(result.orElseThrow().active());
        assertEquals(List.of(TOKEN), requestTokens);
        assertTrue(requestUris.get(0).getPath().endsWith("/internal/users/" + FIRST_ID));
    }

    @Test
    void getUserMapsNotFoundToEmpty() {
        responder = uri -> new Response(404, "{\"detail\":\"missing\"}");

        assertTrue(client.getUser(FIRST_ID).isEmpty());
    }

    @Test
    void getUserMapsProblemDetailAndUnknownStatuses() {
        responder = uri -> new Response(403, "{\"detail\":\"Directory denied\"}");
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
                () -> client.getUser(FIRST_ID));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("Directory denied", denied.getReason());

        responder = uri -> new Response(503, "not-json");
        ResponseStatusException fallback = assertThrows(ResponseStatusException.class,
                () -> client.getUser(FIRST_ID));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, fallback.getStatusCode());
        assertEquals("auth-service: Service Unavailable", fallback.getReason());

        responder = uri -> new Response(599, "{}");
        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> client.getUser(FIRST_ID));
        assertEquals(HttpStatus.BAD_GATEWAY, unknown.getStatusCode());
    }

    @Test
    void getUsersByIdsSkipsMissingAndShortCircuitsEmptyInput() {
        responder = uri -> uri.getPath().endsWith(FIRST_ID.toString())
                ? new Response(200, userJson(FIRST_ID, "ACTIVE"))
                : new Response(404, "{}");

        assertEquals(List.of(FIRST_ID), client.getUsersByIds(List.of(FIRST_ID, SECOND_ID)).stream()
                .map(AuthDirectoryClient.RemoteUser::id).toList());
        int before = requests.get();
        assertEquals(List.of(), client.getUsersByIds(null));
        assertEquals(List.of(), client.getUsersByIds(List.of()));
        assertEquals(before, requests.get());
    }

    @Test
    void listActiveUsersClampsPagingTrimsQueryAndFiltersInactive() {
        responder = uri -> new Response(200, """
                {"content":[
                  %s,
                  %s
                ],"page":0,"size":200,"totalElements":2,"totalPages":1}
                """.formatted(userJson(FIRST_ID, "ACTIVE"), userJson(SECOND_ID, "DISABLED")));

        List<AuthDirectoryClient.RemoteUser> result = client.listActiveUsers(" Ann ", -5, 999);

        assertEquals(List.of(FIRST_ID), result.stream().map(AuthDirectoryClient.RemoteUser::id).toList());
        var query = UriComponentsBuilder.fromUri(requestUris.get(0)).build().getQueryParams();
        assertEquals("ACTIVE", query.getFirst("status"));
        assertEquals("0", query.getFirst("page"));
        assertEquals("200", query.getFirst("size"));
        assertEquals("Ann", query.getFirst("q"));
        assertEquals(TOKEN, requestTokens.get(0));
    }

    @Test
    void listActiveUsersHandlesMissingContentAndBlankQuery() {
        responder = uri -> new Response(200,
                "{\"content\":null,\"page\":0,\"size\":1,\"totalElements\":0,\"totalPages\":0}");

        assertEquals(List.of(), client.listActiveUsers(" ", 3, 0));
        var query = UriComponentsBuilder.fromUri(requestUris.get(0)).build().getQueryParams();
        assertEquals("3", query.getFirst("page"));
        assertEquals("1", query.getFirst("size"));
        assertFalse(query.containsKey("q"));
    }

    @Test
    void missingInternalTokenAndNetworkFailureAreMapped() {
        AuthDirectoryClient unconfigured = new AuthDirectoryClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new InternalTokenGuard(" "), new ObjectMapper());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> unconfigured.getUser(FIRST_ID)).getStatusCode());
        assertEquals(0, requests.get());

        AuthDirectoryClient unreachable = new AuthDirectoryClient(
                "http://127.0.0.1:1", new InternalTokenGuard(TOKEN), new ObjectMapper());
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ResponseStatusException.class,
                () -> unreachable.listActiveUsers(null, 0, 10)).getStatusCode());
    }

    private String userJson(UUID id, String status) {
        return """
                {
                  "id":"%s",
                  "username":"user-%s",
                  "email":"%s@example.test",
                  "displayName":"Test User",
                  "source":"LOCAL",
                  "status":"%s",
                  "department":"Sales",
                  "groups":["EMPLOYEES"],
                  "permissions":["CHAT_READ"],
                  "quotaBytes":1000,
                  "usedBytes":10,
                  "mustChangePassword":false,
                  "totpEnabled":true,
                  "lastLoginAt":"2026-10-20T08:00:00Z",
                  "createdAt":"2026-10-01T08:00:00Z"
                }
                """.formatted(id, id, id, status);
    }

    private record Response(int status, String body) {
    }
}
