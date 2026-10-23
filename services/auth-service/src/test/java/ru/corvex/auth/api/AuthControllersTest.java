package ru.corvex.auth.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.corvex.auth.api.dto.AuthDtos.AppPasswordCreated;
import ru.corvex.auth.api.dto.AuthDtos.AppPasswordView;
import ru.corvex.auth.api.dto.AuthDtos.ChangePasswordRequest;
import ru.corvex.auth.api.dto.AuthDtos.CreateAppPasswordRequest;
import ru.corvex.auth.api.dto.AuthDtos.CreateUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.LoginRequest;
import ru.corvex.auth.api.dto.AuthDtos.MfaRequiredResponse;
import ru.corvex.auth.api.dto.AuthDtos.MfaVerifyRequest;
import ru.corvex.auth.api.dto.AuthDtos.PatchUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.StatsResponse;
import ru.corvex.auth.api.dto.AuthDtos.TokenResponse;
import ru.corvex.auth.api.dto.AuthDtos.TotpCodeRequest;
import ru.corvex.auth.api.dto.AuthDtos.UserPage;
import ru.corvex.auth.api.dto.AuthDtos.UserView;
import ru.corvex.auth.api.dto.AuthDtos.VerifyPasswordRequest;
import ru.corvex.auth.api.dto.AuthDtos.VerifyPasswordResponse;
import ru.corvex.auth.domain.AppPassword;
import ru.corvex.auth.domain.User;
import ru.corvex.auth.service.AuthService;
import ru.corvex.auth.service.AuthService.CreatedAppPassword;
import ru.corvex.auth.service.AuthService.IssuedSession;
import ru.corvex.auth.service.AuthService.LoginFlow;
import ru.corvex.auth.service.AuthService.TotpSetup;
import ru.corvex.auth.service.InternalUserService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllersTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private AuthService auth;
    private InternalUserService internalUsers;
    private AuthController controller;
    private InternalUserController internalController;
    private Jwt jwt;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        auth = mock(AuthService.class);
        internalUsers = mock(InternalUserService.class);
        controller = new AuthController(auth, true, Duration.ofDays(30));
        internalController = new InternalUserController(internalUsers);
        jwt = jwt(USER_ID.toString());
        request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.20");
        request.addHeader("User-Agent", "JUnit");
    }

    @Test
    void loginSuccessSetsStrictHttpOnlyRefreshCookie() {
        IssuedSession issued = issued();
        when(auth.login("ivan", "Password123", "192.0.2.20", "JUnit"))
                .thenReturn(new LoginFlow(issued, null));

        ResponseEntity<?> response = controller.login(
                new LoginRequest("ivan", "Password123"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        TokenResponse body = (TokenResponse) response.getBody();
        assertEquals("access", body.accessToken());
        assertEquals("Bearer", body.tokenType());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains(AuthController.REFRESH_COOKIE + "=refresh-raw"));
        assertTrue(cookie.contains("Path=/api/v1/auth"));
        assertTrue(cookie.contains("Max-Age=2592000"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
    }

    @Test
    void loginWithMfaReturnsAcceptedTicketWithoutCookie() {
        when(auth.login(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new LoginFlow(null, "ticket-1"));

        ResponseEntity<?> response = controller.login(
                new LoginRequest("ivan", "Password123"), request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(new MfaRequiredResponse(true, "ticket-1"), response.getBody());
        assertNull(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE));
    }

    @Test
    void clientIpUsesTrustedRealIpThenLastForwardedAddressAndTruncatesUserAgent() {
        when(auth.login(anyString(), anyString(), anyString(), nullable(String.class)))
                .thenReturn(new LoginFlow(null, "ticket"));
        String longUserAgent = "a".repeat(600);

        MockHttpServletRequest real = request("192.0.2.1", " 203.0.113.5 ", "198.51.100.1", longUserAgent);
        controller.login(new LoginRequest("real", "x"), real);
        verify(auth).login("real", "x", "203.0.113.5", "a".repeat(512));

        MockHttpServletRequest forwarded = request(
                "192.0.2.2", " ", "198.51.100.1, , 203.0.113.9, ", "UA");
        controller.login(new LoginRequest("forwarded", "x"), forwarded);
        verify(auth).login("forwarded", "x", "203.0.113.9", "UA");

        MockHttpServletRequest remote = request("192.0.2.3", null, " , , ", null);
        controller.login(new LoginRequest("remote", "x"), remote);
        verify(auth).login("remote", "x", "192.0.2.3", null);
    }

    @Test
    void mfaVerifyAndRefreshReturnNewCookie() {
        when(auth.verifyMfa("t", "123456", "192.0.2.20", "JUnit")).thenReturn(issued());
        when(auth.refresh("old", "192.0.2.20", "JUnit")).thenReturn(issued());

        assertEquals("access", controller.mfaVerify(
                new MfaVerifyRequest("t", "123456"), request).getBody().accessToken());
        assertEquals("access", controller.refresh("old", request).getBody().accessToken());
    }

    @Test
    void logoutPassesJwtBlacklistDataAndClearsCookie() {
        Instant expires = Instant.now().plusSeconds(600);
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .jti("jti-1")
                .expiresAt(expires)
                .build();

        ResponseEntity<Void> response = controller.logout("raw", token);
        ResponseEntity<Void> anonymous = controller.logout(null, null);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, anonymous.getStatusCode());
        verify(auth).logout("raw", "jti-1", expires);
        verify(auth).logout(null, null, null);
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains(AuthController.REFRESH_COOKIE + "="));
        assertTrue(cookie.contains("Max-Age=0"));
    }

    @Test
    void mePassesJwtGroupsAndPermissionsAsFallback() {
        UserView view = userView();
        Jwt token = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(USER_ID.toString())
                .claim("groups", List.of("USER"))
                .claim("permissions", List.of("MAIL_READ_OWN"))
                .build();
        when(auth.me(USER_ID, List.of("USER"), List.of("MAIL_READ_OWN"))).thenReturn(view);

        assertSame(view, controller.me(token));
    }

    @Test
    void authenticatedEndpointsRejectMissingOrMalformedJwtSubject() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> controller.me(null)).status());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> controller.me(jwt(null))).status());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> controller.me(jwt("not-a-uuid"))).status());
    }

    @Test
    void passwordAndTotpEndpointsDelegate() {
        controller.changePassword(jwt,
                new ChangePasswordRequest("OldPassword1", "NewPassword2"), "refresh");
        when(auth.totpSetup(USER_ID)).thenReturn(new TotpSetup("secret", "otpauth://totp/test"));

        var setup = controller.totpSetup(jwt);
        controller.totpConfirm(jwt, new TotpCodeRequest("123456"));
        controller.totpDisable(jwt, new TotpCodeRequest("654321"));

        assertEquals("secret", setup.secret());
        assertEquals("otpauth://totp/test", setup.otpauthUri());
        verify(auth).changePassword(USER_ID, "OldPassword1", "NewPassword2", "refresh");
        verify(auth).totpConfirm(USER_ID, "123456");
        verify(auth).totpDisable(USER_ID, "654321");
    }

    @Test
    void appPasswordEndpointsMapAndDelegate() {
        AppPassword entity = appPassword();
        when(auth.listAppPasswords(USER_ID)).thenReturn(List.of(entity));
        when(auth.createAppPassword(USER_ID, "Thunderbird"))
                .thenReturn(new CreatedAppPassword(entity, "abcd-1234"));

        List<AppPasswordView> list = controller.appPasswords(jwt);
        ResponseEntity<AppPasswordCreated> created = controller.createAppPassword(
                jwt, new CreateAppPasswordRequest("Thunderbird"));
        controller.deleteAppPassword(jwt, entity.getId());

        assertEquals(entity.getId(), list.get(0).id());
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        assertEquals("abcd-1234", created.getBody().password());
        verify(auth).deleteAppPassword(USER_ID, entity.getId());
    }

    @Test
    void internalControllerDelegatesAllEndpoints() {
        UserView view = userView();
        CreateUserRequest create = new CreateUserRequest(
                "ivan", "ivan@example.test", "Ivan", "Password123", "Sales", null);
        PatchUserRequest patch = new PatchUserRequest("Ivan P.", null, null, null, null);
        UserPage page = new UserPage(List.of(view), 0, 50, 1, 1);
        VerifyPasswordResponse verify = new VerifyPasswordResponse(true, USER_ID, "PASSWORD");
        StatsResponse stats = new StatsResponse(1, 1, 0, 0, 2L, 8L, null);
        when(internalUsers.create(create)).thenReturn(view);
        when(internalUsers.get(USER_ID)).thenReturn(view);
        when(internalUsers.getByUsername("ivan")).thenReturn(view);
        when(internalUsers.getByEmail("ivan@example.test")).thenReturn(view);
        when(internalUsers.list("iv", "ACTIVE", "Sales", 0, 50)).thenReturn(page);
        when(internalUsers.patch(USER_ID, patch)).thenReturn(view);
        when(internalUsers.verifyPassword("ivan", "Password123")).thenReturn(verify);
        when(internalUsers.stats("Sales")).thenReturn(stats);

        assertSame(view, internalController.create(create));
        assertSame(view, internalController.get(USER_ID));
        assertSame(view, internalController.byUsername("ivan"));
        assertSame(view, internalController.byEmail("ivan@example.test"));
        assertSame(page, internalController.list("iv", "ACTIVE", "Sales", 0, 50));
        assertSame(view, internalController.patch(USER_ID, patch));
        internalController.block(USER_ID);
        internalController.disable(USER_ID);
        assertSame(verify, internalController.verifyPassword(
                new VerifyPasswordRequest("ivan", "Password123")));
        assertSame(stats, internalController.stats("Sales"));
        verify(internalUsers).setStatus(USER_ID, User.Status.BLOCKED);
        verify(internalUsers).setStatus(USER_ID, User.Status.DISABLED);
    }

    private MockHttpServletRequest request(String remote, String realIp, String forwarded, String userAgent) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remote);
        if (realIp != null) {
            req.addHeader("X-Real-IP", realIp);
        }
        if (forwarded != null) {
            req.addHeader("X-Forwarded-For", forwarded);
        }
        if (userAgent != null) {
            req.addHeader("User-Agent", userAgent);
        }
        return req;
    }

    private Jwt jwt(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.build();
    }

    private IssuedSession issued() {
        return new IssuedSession("access", 900, "refresh-raw", USER_ID);
    }

    private UserView userView() {
        return new UserView(
                USER_ID, "ivan", "ivan@example.test", "Ivan Petrov",
                "LOCAL", "ACTIVE", "Sales", List.of("USER"),
                List.of("MAIL_READ_OWN"), 5_000L, 0L,
                false, false, null, Instant.parse("2026-01-01T00:00:00Z"));
    }

    private AppPassword appPassword() {
        AppPassword entity = new AppPassword();
        entity.setId(UUID.fromString("22222222-2222-4222-8222-222222222222"));
        entity.setUserId(USER_ID);
        entity.setLabel("Thunderbird");
        entity.setPasswordHash("hash");
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setLastUsedAt(Instant.parse("2026-01-02T00:00:00Z"));
        return entity;
    }
}
