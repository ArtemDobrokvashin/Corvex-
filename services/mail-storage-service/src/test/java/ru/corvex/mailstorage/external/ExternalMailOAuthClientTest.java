package ru.corvex.mailstorage.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Только loopback-заглушка: ни Google, ни Microsoft не вызываются, секретов в тесте нет.
 * Проверяется ровно то, что зависит от нашего кода: параметры запроса, разбор ответа,
 * проверка id_token и категории ошибок.
 */
class ExternalMailOAuthClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static ExternalMailOAuthProperties properties(String baseUrl) {
        return new ExternalMailOAuthProperties("google-client", "google-secret",
                "ms-client", "ms-secret", "common", baseUrl, 5, 10, 30);
    }

    private ExternalMailOAuthClient client(String baseUrl, String responseBody, int status,
                                           AtomicReference<String> lastBody)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (lastBody != null) {
                lastBody.set(new String(body, StandardCharsets.UTF_8));
            }
            byte[] payload = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        });
        server.start();
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        Map<ExternalMailOAuthProvider, ExternalMailOAuthClient.Endpoints> endpoints =
                new EnumMap<>(ExternalMailOAuthProvider.class);
        for (ExternalMailOAuthProvider provider : ExternalMailOAuthProvider.values()) {
            endpoints.put(provider, new ExternalMailOAuthClient.Endpoints(origin + "/authorize", origin + "/token"));
        }
        return new ExternalMailOAuthClient(properties(baseUrl), new ObjectMapper(), endpoints);
    }

    private static String idToken(String audience, String issuer, long expires, String email) {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"aud\":\"" + audience + "\",\"iss\":\"" + issuer + "\",\"exp\":" + expires
                + ",\"email\":\"" + email + "\"}");
        return header + "." + payload + ".signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void googleAuthorizationUrlRequestsOfflineAccessAndPkce() {
        ExternalMailOAuthClient client = new ExternalMailOAuthClient(properties("https://mail.example.test"),
                new ObjectMapper());

        String url = client.authorizationUrl(ExternalMailOAuthProvider.GOOGLE, "state-value", "challenge-value");

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), url);
        assertTrue(url.contains("client_id=google-client"), url);
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fmail.example.test%2Fapi%2Fv1%2Fmail%2Fexternal-accounts%2Foauth%2Fcallback"), url);
        assertTrue(url.contains("code_challenge=challenge-value"), url);
        assertTrue(url.contains("code_challenge_method=S256"), url);
        assertTrue(url.contains("access_type=offline"), "без offline Google не выдаст refresh token");
        assertTrue(url.contains("prompt=consent"), url);
        assertTrue(url.contains("state=state-value"), url);
    }

    @Test
    void microsoftAuthorizationUrlUsesTenantAndNoGoogleParameters() {
        ExternalMailOAuthClient client = new ExternalMailOAuthClient(properties("https://mail.example.test"),
                new ObjectMapper());

        String url = client.authorizationUrl(ExternalMailOAuthProvider.MICROSOFT, "s", "c");

        assertTrue(url.startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?"), url);
        assertTrue(url.contains("scope=openid+email+offline_access"), url);
        assertTrue(!url.contains("access_type"), "параметр Google не должен попадать в запрос Microsoft");
    }

    @Test
    void exchangeSendsPkceVerifierAndParsesTokens() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        ExternalMailOAuthClient client = client("https://mail.example.test",
                "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600,"
                        + "\"scope\":\"https://mail.google.com/\",\"id_token\":\""
                        + idToken("google-client", "https://accounts.google.com", Instant.now().plusSeconds(600).getEpochSecond(),
                        "ivan@company.ru") + "\"}",
                200, body);

        ExternalMailOAuthClient.Tokens tokens = client.exchange(ExternalMailOAuthProvider.GOOGLE, "code-1", "verifier-1");

        assertEquals("at-1", tokens.accessToken());
        assertEquals("rt-1", tokens.refreshToken());
        assertTrue(tokens.expiresAt().isAfter(Instant.now().plusSeconds(3000)));
        assertTrue(body.get().contains("grant_type=authorization_code"), body.get());
        assertTrue(body.get().contains("code_verifier=verifier-1"), body.get());
        assertTrue(body.get().contains("code=code-1"), body.get());
        assertEquals("ivan@company.ru", client.identityEmail(ExternalMailOAuthProvider.GOOGLE, tokens));
    }

    @Test
    void refreshWithoutNewRefreshTokenKeepsWorking() throws Exception {
        ExternalMailOAuthClient client = client("https://mail.example.test",
                "{\"access_token\":\"at-2\",\"expires_in\":1800}", 200, null);

        ExternalMailOAuthClient.Tokens tokens = client.refresh(ExternalMailOAuthProvider.MICROSOFT, "rt-old");

        assertEquals("at-2", tokens.accessToken());
        assertNull(tokens.refreshToken(), "провайдер не обязан возвращать refresh token повторно");
        assertNull(tokens.idToken());
    }

    @Test
    void invalidGrantIsRejectedAndNotTemporary() throws Exception {
        ExternalMailOAuthClient client = client("https://mail.example.test",
                "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been revoked\"}", 400, null);

        var error = assertThrows(ExternalMailOAuthClient.OAuthCallException.class,
                () -> client.refresh(ExternalMailOAuthProvider.GOOGLE, "rt"));

        assertEquals(ExternalMailOAuthClient.OAuthCallException.Kind.REJECTED, error.kind());
        assertTrue(!error.getMessage().contains("revoked"), "текст провайдера не пересказывается");
        assertTrue(!error.getMessage().contains("rt"), "refresh token не должен попасть в сообщение");
    }

    @Test
    void misconfiguredClientIdIsNotRetriedForever() throws Exception {
        ExternalMailOAuthClient client = client("https://mail.example.test",
                "{\"error\":\"invalid_client\"}", 401, null);

        var error = assertThrows(ExternalMailOAuthClient.OAuthCallException.class,
                () -> client.exchange(ExternalMailOAuthProvider.MICROSOFT, "c", "v"));

        assertEquals(ExternalMailOAuthClient.OAuthCallException.Kind.MISCONFIGURED, error.kind());
    }

    @Test
    void identityMustMatchAudienceIssuerAndExpiry() throws Exception {
        ExternalMailOAuthClient client = client("https://mail.example.test", "{}", 200, null);

        String foreignAudience = idToken("other-client", "https://accounts.google.com",
                Instant.now().plusSeconds(600).getEpochSecond(), "ivan@company.ru");
        var wrongAudience = assertThrows(ExternalMailOAuthClient.OAuthCallException.class,
                () -> client.identityEmail(ExternalMailOAuthProvider.GOOGLE,
                        new ExternalMailOAuthClient.Tokens("at", null, Instant.now().plusSeconds(600), null, foreignAudience)));
        assertEquals(ExternalMailOAuthClient.OAuthCallException.Kind.MISCONFIGURED, wrongAudience.kind());

        String foreignIssuer = idToken("google-client", "https://evil.example",
                Instant.now().plusSeconds(600).getEpochSecond(), "ivan@company.ru");
        var wrongIssuer = assertThrows(ExternalMailOAuthClient.OAuthCallException.class,
                () -> client.identityEmail(ExternalMailOAuthProvider.GOOGLE,
                        new ExternalMailOAuthClient.Tokens("at", null, Instant.now().plusSeconds(600), null, foreignIssuer)));
        assertEquals(ExternalMailOAuthClient.OAuthCallException.Kind.MISCONFIGURED, wrongIssuer.kind());

        String expired = idToken("google-client", "https://accounts.google.com",
                Instant.now().minusSeconds(600).getEpochSecond(), "ivan@company.ru");
        var expiredAnswer = assertThrows(ExternalMailOAuthClient.OAuthCallException.class,
                () -> client.identityEmail(ExternalMailOAuthProvider.GOOGLE,
                        new ExternalMailOAuthClient.Tokens("at", null, Instant.now().plusSeconds(600), null, expired)));
        assertEquals(ExternalMailOAuthClient.OAuthCallException.Kind.TEMPORARY, expiredAnswer.kind());
    }

    @Test
    void microsoftPreferredUsernameIsAccepted() throws Exception {
        ExternalMailOAuthClient client = client("https://mail.example.test", "{}", 200, null);
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"aud\":\"ms-client\",\"iss\":\"https://login.microsoftonline.com/tenant/v2.0\","
                + "\"exp\":" + Instant.now().plusSeconds(600).getEpochSecond()
                + ",\"preferred_username\":\"Ivan@Company.RU\"}");
        var tokens = new ExternalMailOAuthClient.Tokens("at", null, Instant.now().plusSeconds(600), null,
                header + "." + payload + ".sig");

        assertEquals("ivan@company.ru", client.identityEmail(ExternalMailOAuthProvider.MICROSOFT, tokens));
    }

    @Test
    void codeChallengeMatchesRfc7636Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                ExternalMailOAuthClient.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void unreachableProviderIsTemporary() throws Exception {
        ExternalMailOAuthClient client = client("https://mail.example.test", "{}", 200, null);
        server.stop(0);
        server = null;

        var error = assertThrows(ExternalMailOAuthClient.OAuthCallException.class,
                () -> client.refresh(ExternalMailOAuthProvider.GOOGLE, "rt"));

        assertEquals(ExternalMailOAuthClient.OAuthCallException.Kind.TEMPORARY, error.kind());
    }

    @Test
    void redirectUriNeedsPublicBaseUrl() {
        ExternalMailOAuthProperties withoutBase = new ExternalMailOAuthProperties("id", "secret", null, null,
                "common", "", 5, 10, 30);

        assertNull(withoutBase.redirectUri());
        assertTrue(withoutBase.enabled(ExternalMailOAuthProvider.GOOGLE));
        assertTrue(!withoutBase.enabled(ExternalMailOAuthProvider.MICROSOFT));
    }
}
