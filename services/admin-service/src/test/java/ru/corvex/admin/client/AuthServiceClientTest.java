package ru.corvex.admin.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.client.AuthServiceClient.RemotePage;
import ru.corvex.admin.client.AuthServiceClient.RemoteStats;
import ru.corvex.admin.client.AuthServiceClient.RemoteUser;
import ru.corvex.admin.client.FakeHttpServer.Request;
import ru.corvex.admin.client.FakeHttpServer.Response;
import ru.corvex.admin.security.InternalApiTokenFilter;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceClientTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String TOKEN = "internal-secret";

    private FakeHttpServer server;

    @AfterEach
    void tearDown() throws Exception {
        LocaleContextHolder.resetLocaleContext();
        if (server != null) {
            server.close();
        }
    }

    @Test
    void createUserSendsInternalTokenLocaleAndJsonBody() throws Exception {
        server = new FakeHttpServer(request -> Response.json(201, userJson("ACTIVE")));
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        RemoteUser result = client().createUser(
                "ivan", "ivan@example.test", "Ivan Petrov", "Secret-123", "Sales", 5_000L);

        assertEquals(USER_ID, result.id());
        assertEquals("local", result.source());
        assertEquals(Instant.parse("2026-01-03T04:05:06Z"), result.lastLoginAt());
        Request request = server.lastRequest();
        assertEquals("POST", request.method());
        assertEquals("/internal/users", request.target());
        assertEquals(TOKEN, request.header(InternalApiTokenFilter.HEADER));
        assertTrue(request.header("accept-language").contains("en"));
        assertTrue(request.body().contains("\"username\":\"ivan\""));
        assertTrue(request.body().contains("\"quotaBytes\":5000"));
    }

    @Test
    void getFindPatchBlockAndDisableUseInternalRoutes() throws Exception {
        server = new FakeHttpServer(request -> {
            if (request.method().equals("POST")) {
                return new Response(204, "text/plain", "");
            }
            return Response.json(200, userJson(request.method().equals("PATCH") ? "BLOCKED" : "ACTIVE"));
        });
        AuthServiceClient client = client();

        assertEquals("ivan", client.getUser(USER_ID).username());
        assertEquals(Optional.of(USER_ID), client.findByUsername("ivan").map(RemoteUser::id));
        assertEquals("BLOCKED", client.patchUser(USER_ID, Map.of("status", "BLOCKED")).status());
        client.blockUser(USER_ID);
        client.disableUser(USER_ID);

        List<Request> requests = server.requests();
        assertEquals("/internal/users/" + USER_ID, requests.get(0).target());
        assertEquals("/internal/users/by-username/ivan", requests.get(1).target());
        assertEquals("PATCH", requests.get(2).method());
        assertEquals("/internal/users/" + USER_ID + "/block", requests.get(3).target());
        assertEquals("/internal/users/" + USER_ID + "/disable", requests.get(4).target());
    }

    @Test
    void findByUsernameConvertsOnly404ToEmpty() throws Exception {
        server = new FakeHttpServer(request -> request.target().endsWith("/missing")
                ? Response.json(404, problem("Пользователь не существует"))
                : Response.json(503, problem("temporary failure")));

        assertTrue(client().findByUsername("missing").isEmpty());
        ApiException error = assertThrows(ApiException.class,
                () -> client().findByUsername("broken"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertEquals("temporary failure", error.getMessage());
    }

    @Test
    void listUsersOmitsBlankFiltersAndParsesPage() throws Exception {
        server = new FakeHttpServer(request -> Response.json(200, """
                {"content":[%s],"page":3,"size":25,"totalElements":61,"totalPages":3}
                """.formatted(userJson("ACTIVE"))));

        RemotePage filtered = client().listUsers("ivan", "ACTIVE", "Sales", 3, 25);
        RemotePage unfiltered = client().listUsers(" ", null, "", 0, 50);

        assertEquals(61, filtered.totalElements());
        assertEquals(1, unfiltered.content().size());
        String firstTarget = server.requests().get(0).target();
        String secondTarget = server.requests().get(1).target();
        assertTrue(firstTarget.contains("q=ivan"));
        assertTrue(firstTarget.contains("status=ACTIVE"));
        assertTrue(firstTarget.contains("department=Sales"));
        assertTrue(secondTarget.contains("page=0"));
        assertTrue(!secondTarget.contains("q="));
        assertTrue(!secondTarget.contains("status="));
        assertTrue(!secondTarget.contains("department="));
    }

    @Test
    void statsIncludesDepartmentOnlyWhenPresent() throws Exception {
        server = new FakeHttpServer(request -> Response.json(200, """
                {"totalUsers":20,"activeUsers":15,"blockedUsers":2,"disabledUsers":3,
                 "loginsLast24h":7,"loginsLast7d":40,"department":"Sales"}
                """));

        RemoteStats stats = client().stats("Sales");
        client().stats(" ");

        assertEquals(20, stats.totalUsers());
        assertEquals("Sales", stats.department());
        assertTrue(server.requests().get(0).target().contains("department=Sales"));
        assertEquals("/internal/stats", server.requests().get(1).target());
    }

    @Test
    void problemDetailsArePreservedWithUpstreamStatus() throws Exception {
        server = new FakeHttpServer(request -> Response.json(
                409, problem("Логин уже занят: ivan")));

        ApiException error = assertThrows(ApiException.class,
                () -> client().createUser("ivan", "a@b.test", "Ivan", "x", null, null));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("Логин уже занят: ivan", error.getMessage());
    }

    @Test
    void missingDetailInvalidBodyAndUnknownStatusUseSafeFallbacks() throws Exception {
        server = new FakeHttpServer(request -> {
            if (request.target().contains("00000000")) {
                return Response.json(422, "{}");
            }
            if (request.target().contains("22222222")) {
                return new Response(599, "text/plain", "weird upstream");
            }
            return new Response(500, "text/plain", "not json");
        });
        AuthServiceClient client = client();

        ApiException missing = assertThrows(ApiException.class,
                () -> client.getUser(UUID.fromString("00000000-0000-4000-8000-000000000000")));
        ApiException invalid = assertThrows(ApiException.class, () -> client.getUser(USER_ID));
        ApiException unknown = assertThrows(ApiException.class,
                () -> client.getUser(UUID.fromString("22222222-2222-4222-8222-222222222222")));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, missing.status());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, invalid.status());
        assertEquals(HttpStatus.BAD_GATEWAY, unknown.status());
        assertTrue(missing.getMessage().startsWith("auth-service:"));
        assertTrue(invalid.getMessage().startsWith("auth-service:"));
        assertTrue(unknown.getMessage().startsWith("auth-service:"));
    }

    @Test
    void transportFailuresBecomeBadGateway() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        AuthServiceClient client = new AuthServiceClient(
                "http://127.0.0.1:" + closedPort, TOKEN);

        ApiException callError = assertThrows(ApiException.class, () -> client.getUser(USER_ID));
        ApiException findError = assertThrows(ApiException.class,
                () -> client.findByUsername("ivan"));

        assertEquals(HttpStatus.BAD_GATEWAY, callError.status());
        assertEquals("auth-service недоступен", callError.getMessage());
        assertEquals(HttpStatus.BAD_GATEWAY, findError.status());
        assertTrue(findError.getMessage().startsWith("auth-service недоступен:"));
    }

    private AuthServiceClient client() {
        return new AuthServiceClient(server.baseUrl(), TOKEN);
    }

    private String userJson(String status) {
        return """
                {"id":"%s","username":"ivan","email":"ivan@example.test",
                 "displayName":"Ivan Petrov","source":"local","status":"%s",
                 "department":"Sales","groups":["USER"],"permissions":["MAIL_READ_OWN"],
                 "quotaBytes":5000,"usedBytes":250,"mustChangePassword":true,
                 "totpEnabled":false,"lastLoginAt":"2026-01-03T04:05:06Z",
                 "createdAt":"2026-01-01T00:00:00Z"}
                """.formatted(USER_ID, status);
    }

    private String problem(String detail) {
        return "{\"title\":\"Problem\",\"status\":0,\"detail\":\"" + detail + "\"}";
    }
}
