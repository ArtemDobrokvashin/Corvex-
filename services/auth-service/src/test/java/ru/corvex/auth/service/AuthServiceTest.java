package ru.corvex.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.corvex.auth.api.ApiException;
import ru.corvex.auth.api.dto.AuthDtos.UserView;
import ru.corvex.auth.client.AdminAccessClient;
import ru.corvex.auth.client.AdminAccessClient.AccessInfo;
import ru.corvex.auth.domain.AppPassword;
import ru.corvex.auth.domain.LoginAttempt;
import ru.corvex.auth.domain.RefreshSession;
import ru.corvex.auth.domain.User;
import ru.corvex.auth.repository.AppPasswordRepository;
import ru.corvex.auth.repository.LoginAttemptRepository;
import ru.corvex.auth.repository.RefreshSessionRepository;
import ru.corvex.auth.repository.UserRepository;
import ru.corvex.auth.security.TokenService;
import ru.corvex.auth.security.TotpSecretCipher;
import ru.corvex.auth.service.AuthService.CreatedAppPassword;
import ru.corvex.auth.service.AuthService.IssuedSession;
import ru.corvex.auth.service.AuthService.LoginFlow;
import ru.corvex.auth.service.AuthService.TotpSetup;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String IP = "192.0.2.10";
    private static final String UA = "JUnit Browser";
    private static final String TOTP_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String DUMMY_HASH = "enc(corvex-nonexistent-user-dummy)";

    private UserRepository users;
    private RefreshSessionRepository sessions;
    private LoginAttemptRepository attempts;
    private AppPasswordRepository appPasswords;
    private PasswordEncoder passwordEncoder;
    private TokenService tokenService;
    private AdminAccessClient adminAccess;
    private LoginAttemptRecorder attemptRecorder;
    private SessionRevoker sessionRevoker;
    private TotpSecretCipher totpCipher;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private AuthService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        users = mock(UserRepository.class);
        sessions = mock(RefreshSessionRepository.class);
        attempts = mock(LoginAttemptRepository.class);
        appPasswords = mock(AppPasswordRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenService = mock(TokenService.class);
        adminAccess = mock(AdminAccessClient.class);
        attemptRecorder = mock(LoginAttemptRecorder.class);
        sessionRevoker = mock(SessionRevoker.class);
        totpCipher = mock(TotpSecretCipher.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);

        when(passwordEncoder.encode(anyString()))
                .thenAnswer(invocation -> "enc(" + invocation.getArgument(0) + ")");
        when(redis.opsForValue()).thenReturn(valueOps);
        when(attempts.findFirstByUsernameIgnoreCaseAndSuccessTrueOrderByCreatedAtDesc(anyString()))
                .thenReturn(Optional.empty());
        when(attempts.countFailuresSince(anyString(), any(Instant.class))).thenReturn(0L);
        when(adminAccess.fetch(any())).thenReturn(Optional.of(
                new AccessInfo(List.of("USER"), List.of("MAIL_READ_OWN"))));
        when(tokenService.issueAccessToken(any(), anyString(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> new TokenService.AccessToken(
                        "access-token", Instant.now().plusSeconds(900)));
        when(users.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            if (user.getId() == null) {
                user.setId(UUID.randomUUID());
            }
            return user;
        });
        when(sessions.save(any(RefreshSession.class))).thenAnswer(invocation -> {
            RefreshSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(UUID.randomUUID());
            }
            return session;
        });
        when(totpCipher.encrypt(anyString())).thenReturn("enc-secret");
        when(totpCipher.decrypt("enc-secret")).thenReturn(TOTP_SECRET);

        service = new AuthService(
                users, sessions, attempts, appPasswords, passwordEncoder, tokenService,
                adminAccess, attemptRecorder, sessionRevoker, totpCipher, redis,
                Duration.ofDays(30), 5, Duration.ofMinutes(15));
    }

    // ---------- login ----------

    @Test
    void loginIssuesSessionRecordsSuccessAndStoresOnlyRefreshHash() {
        User user = user();
        LoginAttempt lastSuccess = attempt(Instant.now().minusSeconds(60));
        when(attempts.findFirstByUsernameIgnoreCaseAndSuccessTrueOrderByCreatedAtDesc("ivan"))
                .thenReturn(Optional.of(lastSuccess));
        when(attempts.countFailuresSince("ivan", lastSuccess.getCreatedAt())).thenReturn(2L);
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", "hash")).thenReturn(true);

        LoginFlow flow = service.login("  IVAN  ", "Password123", IP, UA);

        assertFalse(flow.mfaRequired());
        IssuedSession issued = flow.issued();
        assertEquals("access-token", issued.accessToken());
        assertTrue(issued.expiresInSeconds() > 0 && issued.expiresInSeconds() <= 900);
        assertNotNull(user.getLastLoginAt());
        ArgumentCaptor<RefreshSession> captor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessions).save(captor.capture());
        assertEquals(AuthService.sha256Hex(issued.refreshTokenRaw()), captor.getValue().getTokenHash());
        assertFalse(captor.getValue().isMfaVerified());
        verify(tokenService).issueAccessToken(
                USER_ID, "ivan", List.of("USER"), List.of("MAIL_READ_OWN"), false);
        verify(attemptRecorder).record("ivan", IP, true, LoginAttempt.REASON_OK);
    }

    @Test
    void loginUsesDummyHashForUnknownAndPasswordlessUsers() {
        when(users.findByLogin("ghost")).thenReturn(Optional.empty());

        ApiException unknown = assertThrows(ApiException.class,
                () -> service.login("ghost", "Password123", IP, UA));
        assertEquals(HttpStatus.UNAUTHORIZED, unknown.status());
        verify(passwordEncoder).matches("Password123", DUMMY_HASH);
        verify(attemptRecorder).record("ghost", IP, false, LoginAttempt.REASON_BAD_CREDENTIALS);

        User ldapUser = user();
        ldapUser.setSource(User.Source.AD_LDAP);
        ldapUser.setPasswordHash(null);
        when(users.findByLogin("ivan")).thenReturn(Optional.of(ldapUser));
        assertThrows(ApiException.class, () -> service.login("ivan", "Password123", IP, UA));
        verify(passwordEncoder, never()).matches(eq("Password123"), eq("hash"));
    }

    @Test
    void loginRejectsWrongPassword() {
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user()));

        assertThrows(ApiException.class, () -> service.login("ivan", "wrong", IP, UA));

        verify(attemptRecorder).record("ivan", IP, false, LoginAttempt.REASON_BAD_CREDENTIALS);
        verify(tokenService, never()).issueAccessToken(any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void loginRejectsBlockedUserAfterCorrectPassword() {
        User user = user();
        user.setStatus(User.Status.BLOCKED);
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", "hash")).thenReturn(true);

        ApiException error = assertThrows(ApiException.class,
                () -> service.login("ivan", "Password123", IP, UA));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        verify(attemptRecorder).record("ivan", IP, false, LoginAttempt.REASON_ACCOUNT_NOT_ACTIVE);
    }

    @Test
    void loginLocksOutBeforeUserLookupWhenFailureLimitReached() {
        LoginAttempt oldSuccess = attempt(Instant.now().minusSeconds(3600));
        when(attempts.findFirstByUsernameIgnoreCaseAndSuccessTrueOrderByCreatedAtDesc("ivan"))
                .thenReturn(Optional.of(oldSuccess));
        when(attempts.countFailuresSince(eq("ivan"), any(Instant.class))).thenReturn(5L);

        ApiException error = assertThrows(ApiException.class,
                () -> service.login("ivan", "Password123", IP, UA));

        assertEquals(HttpStatus.LOCKED, error.status());
        assertTrue(error.getMessage().contains("15"));
        verify(attemptRecorder).record("ivan", IP, false, LoginAttempt.REASON_LOCKED);
        verify(users, never()).findByLogin(anyString());
    }

    @Test
    void loginCreatesMfaTicketForTotpUser() {
        User user = user();
        user.setTotpEnabled(true);
        user.setTotpSecretEnc("enc-secret");
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", "hash")).thenReturn(true);

        LoginFlow flow = service.login("ivan", "Password123", IP, UA);

        assertTrue(flow.mfaRequired());
        assertNull(flow.issued());
        assertEquals(43, flow.mfaTicket().length());
        verify(valueOps).set("mfa:ticket:" + flow.mfaTicket(), USER_ID + ":0", Duration.ofMinutes(5));
        verify(tokenService, never()).issueAccessToken(any(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void loginReturnsServiceUnavailableWhenRedisCannotStoreMfaTicket() {
        User user = user();
        user.setTotpEnabled(true);
        when(users.findByLogin("ivan")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password123", "hash")).thenReturn(true);
        doThrow(new IllegalStateException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        ApiException error = assertThrows(ApiException.class,
                () -> service.login("ivan", "Password123", IP, UA));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    }

    // ---------- MFA ----------

    @Test
    void verifyMfaRejectsExpiredTicketAndRedisFailure() {
        when(valueOps.get("mfa:ticket:missing")).thenReturn(null);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> service.verifyMfa("missing", "123456", IP, UA)).status());

        when(valueOps.get("mfa:ticket:broken")).thenThrow(new IllegalStateException("down"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ApiException.class,
                () -> service.verifyMfa("broken", "123456", IP, UA)).status());
    }

    @Test
    void verifyMfaRejectsTicketForMissingUser() {
        when(valueOps.get("mfa:ticket:t")).thenReturn(USER_ID + ":0");
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.verifyMfa("t", currentTotp(), IP, UA));
    }

    @Test
    void verifyMfaCountsWrongCodesAndDeletesTicketAtLimit() {
        User user = totpUser();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(valueOps.get("mfa:ticket:t")).thenReturn(USER_ID.toString());

        assertThrows(ApiException.class, () -> service.verifyMfa("t", invalidTotp(), IP, UA));
        verify(valueOps).set("mfa:ticket:t", USER_ID + ":1", Duration.ofMinutes(5));
        verify(attemptRecorder).record("ivan", IP, false, LoginAttempt.REASON_BAD_TOTP);

        when(valueOps.get("mfa:ticket:last")).thenReturn(USER_ID + ":4");
        assertThrows(ApiException.class, () -> service.verifyMfa("last", invalidTotp(), IP, UA));
        verify(redis).delete("mfa:ticket:last");
    }

    @Test
    void verifyMfaTreatsMissingSecretAsWrongCodeWithoutDecrypting() {
        User user = totpUser();
        user.setTotpSecretEnc(null);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(valueOps.get("mfa:ticket:t")).thenReturn(USER_ID + ":2");

        assertThrows(ApiException.class, () -> service.verifyMfa("t", "123456", IP, UA));
        verify(totpCipher, never()).decrypt(anyString());
    }

    @Test
    void verifyMfaDeletesTicketBeforeRejectingBlockedUser() {
        User user = totpUser();
        user.setStatus(User.Status.DISABLED);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(valueOps.get("mfa:ticket:t")).thenReturn(USER_ID + ":0");

        ApiException error = assertThrows(ApiException.class,
                () -> service.verifyMfa("t", currentTotp(), IP, UA));

        assertEquals(HttpStatus.FORBIDDEN, error.status());
        verify(redis).delete("mfa:ticket:t");
    }

    @Test
    void verifyMfaIssuesMfaVerifiedSession() {
        when(users.findById(USER_ID)).thenReturn(Optional.of(totpUser()));
        when(valueOps.get("mfa:ticket:t")).thenReturn(USER_ID + ":1");

        IssuedSession issued = service.verifyMfa("t", currentTotp(), IP, UA);

        assertEquals(USER_ID, issued.userId());
        verify(redis).delete("mfa:ticket:t");
        verify(tokenService).issueAccessToken(
                USER_ID, "ivan", List.of("USER"), List.of("MAIL_READ_OWN"), true);
        ArgumentCaptor<RefreshSession> captor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessions).save(captor.capture());
        assertTrue(captor.getValue().isMfaVerified());
    }

    // ---------- refresh / logout ----------

    @Test
    void refreshRejectsBlankAndUnknownTokens() {
        assertThrows(ApiException.class, () -> service.refresh(null, IP, UA));
        assertThrows(ApiException.class, () -> service.refresh(" ", IP, UA));
        when(sessions.findByTokenHash(AuthService.sha256Hex("raw"))).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.refresh("raw", IP, UA));
    }

    @Test
    void refreshRevokesAllSessionsWhenRevokedTokenIsReused() {
        RefreshSession old = refreshSession(Instant.now().plusSeconds(3600), Instant.now(), true);
        stubRefreshLookup(old);

        assertThrows(ApiException.class, () -> service.refresh("raw", IP, UA));
        verify(sessionRevoker).revokeAll(USER_ID);
    }

    @Test
    void refreshRejectsExpiredTokenWithoutGlobalRevocation() {
        stubRefreshLookup(refreshSession(Instant.now().minusSeconds(1), null, false));

        assertThrows(ApiException.class, () -> service.refresh("raw", IP, UA));
        verify(sessionRevoker, never()).revokeAll(any());
    }

    @Test
    void refreshRejectsMissingUser() {
        stubRefreshLookup(refreshSession(Instant.now().plusSeconds(3600), null, false));
        when(users.findById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.refresh("raw", IP, UA));
    }

    @Test
    void refreshRevokesAllWhenUserNoLongerActive() {
        stubRefreshLookup(refreshSession(Instant.now().plusSeconds(3600), null, false));
        User user = user();
        user.setStatus(User.Status.BLOCKED);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThrows(ApiException.class, () -> service.refresh("raw", IP, UA));
        verify(sessionRevoker).revokeAll(USER_ID);
    }

    @Test
    void refreshRotatesTokenAndPreservesMfaStateWithoutRecordingNewLogin() {
        RefreshSession old = refreshSession(Instant.now().plusSeconds(3600), null, true);
        stubRefreshLookup(old);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user()));

        IssuedSession issued = service.refresh("raw", IP, UA);

        assertNotNull(old.getRevokedAt());
        ArgumentCaptor<RefreshSession> captor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessions, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(old, captor.getAllValues().get(0));
        assertEquals(AuthService.sha256Hex(issued.refreshTokenRaw()),
                captor.getAllValues().get(1).getTokenHash());
        assertTrue(captor.getAllValues().get(1).isMfaVerified());
        verify(attemptRecorder, never()).record(anyString(), anyString(), eq(true), anyString());
        verify(users, never()).save(any());
    }

    @Test
    void logoutRevokesRefreshAndBlacklistsAccessToken() {
        RefreshSession session = refreshSession(Instant.now().plusSeconds(3600), null, false);
        stubRefreshLookup(session);

        service.logout("raw", "jti-1", Instant.now().plusSeconds(120));

        assertNotNull(session.getRevokedAt());
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(eq("jwt:blacklist:jti-1"), eq("1"), ttl.capture());
        assertTrue(ttl.getValue().getSeconds() > 0 && ttl.getValue().getSeconds() <= 120);
    }

    @Test
    void logoutIsIdempotentAndSkipsExpiredBlacklistEntries() {
        RefreshSession session = refreshSession(Instant.now().plusSeconds(3600), Instant.now(), false);
        stubRefreshLookup(session);

        service.logout("raw", "expired", Instant.now().minusSeconds(1));
        service.logout(" ", null, null);

        verify(sessions, never()).save(any());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void logoutSwallowsRedisBlacklistFailure() {
        doThrow(new IllegalStateException("redis down"))
                .when(valueOps).set(eq("jwt:blacklist:jti"), eq("1"), any(Duration.class));

        assertDoesNotThrow(() -> service.logout(null, "jti", Instant.now().plusSeconds(60)));
    }

    // ---------- profile / password ----------

    @Test
    void meUsesAdminAccessOrJwtClaimFallback() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        UserView live = service.me(USER_ID, List.of("OLD"), List.of("OLD_PERMISSION"));
        assertEquals(List.of("USER"), live.groups());

        when(adminAccess.fetch(USER_ID)).thenReturn(Optional.empty());
        UserView fallback = service.me(USER_ID, null, List.of("CHAT_READ"));
        assertEquals(List.of(), fallback.groups());
        assertEquals(List.of("CHAT_READ"), fallback.permissions());
    }

    @Test
    void meRejectsMissingUser() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.me(USER_ID, null, null));
    }

    @Test
    void changePasswordEncodesAndRevokesOtherSessions() {
        User user = user();
        user.setMustChangePassword(true);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPassword1", "hash")).thenReturn(true);

        service.changePassword(USER_ID, "OldPassword1", "NewPassword2", "current-refresh");

        assertEquals("enc(NewPassword2)", user.getPasswordHash());
        assertFalse(user.isMustChangePassword());
        verify(sessionRevoker).revokeAllExcept(
                USER_ID, AuthService.sha256Hex("current-refresh"));
    }

    @Test
    void changePasswordWithoutCurrentRefreshRevokesAllSessions() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPassword1", "hash")).thenReturn(true);

        service.changePassword(USER_ID, "OldPassword1", "NewPassword2", " ");

        verify(sessionRevoker).revokeAllExcept(USER_ID, null);
    }

    @Test
    void changePasswordRejectsWrongSameAndWeakPasswords() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        assertThrows(ApiException.class,
                () -> service.changePassword(USER_ID, "wrong", "NewPassword2", null));

        when(passwordEncoder.matches("OldPassword1", "hash")).thenReturn(true);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class,
                () -> service.changePassword(USER_ID, "OldPassword1", "OldPassword1", null)).status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ApiException.class,
                () -> service.changePassword(USER_ID, "OldPassword1", "short1", null)).status());

        user.setPasswordHash(null);
        assertThrows(ApiException.class,
                () -> service.changePassword(USER_ID, "anything", "NewPassword2", null));
    }

    @Test
    void changePasswordRejectsMissingUser() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(ApiException.class,
                () -> service.changePassword(USER_ID, "old", "NewPassword2", null));
    }

    // ---------- TOTP management ----------

    @Test
    void totpSetupStoresEncryptedPendingSecretAndReturnsOtpauthUri() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));

        TotpSetup setup = service.totpSetup(USER_ID);

        assertEquals(32, setup.secret().length());
        assertTrue(setup.secret().matches("[A-Z2-7]{32}"));
        assertTrue(setup.otpauthUri().startsWith("otpauth://totp/Corvex%3Aivan?secret=" + setup.secret()));
        assertEquals("enc-secret", user.getTotpSecretEnc());
        assertFalse(user.isTotpEnabled());
        verify(totpCipher).encrypt(setup.secret());
    }

    @Test
    void totpSetupRejectsAlreadyEnabledAndMissingUser() {
        User user = totpUser();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        assertThrows(ApiException.class, () -> service.totpSetup(USER_ID));

        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.totpSetup(USER_ID));
    }

    @Test
    void totpConfirmEnablesOnlyAfterValidCode() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        assertThrows(ApiException.class, () -> service.totpConfirm(USER_ID, "123456"));

        user.setTotpSecretEnc("enc-secret");
        assertThrows(ApiException.class, () -> service.totpConfirm(USER_ID, invalidTotp()));
        assertFalse(user.isTotpEnabled());

        service.totpConfirm(USER_ID, currentTotp());
        assertTrue(user.isTotpEnabled());
    }

    @Test
    void totpConfirmRejectsMissingUser() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.totpConfirm(USER_ID, "123456"));
    }

    @Test
    void totpDisableRequiresEnabledStateAndValidCodeThenClearsSecret() {
        User user = user();
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        assertThrows(ApiException.class, () -> service.totpDisable(USER_ID, "123456"));

        user.setTotpEnabled(true);
        user.setTotpSecretEnc("enc-secret");
        assertThrows(ApiException.class, () -> service.totpDisable(USER_ID, invalidTotp()));
        service.totpDisable(USER_ID, currentTotp());
        assertFalse(user.isTotpEnabled());
        assertNull(user.getTotpSecretEnc());
    }

    @Test
    void totpDisableRejectsMissingUser() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.totpDisable(USER_ID, "123456"));
    }

    // ---------- app passwords ----------

    @Test
    void appPasswordLifecycleUsesOwnerScopedRepositoryQueries() {
        User user = user();
        AppPassword existing = new AppPassword();
        existing.setId(UUID.randomUUID());
        existing.setUserId(USER_ID);
        when(users.findById(USER_ID)).thenReturn(Optional.of(user));
        when(appPasswords.findAllByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(existing));
        when(appPasswords.save(any(AppPassword.class))).thenAnswer(invocation -> {
            AppPassword p = invocation.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        assertEquals(List.of(existing), service.listAppPasswords(USER_ID));
        CreatedAppPassword created = service.createAppPassword(USER_ID, "  Thunderbird  ");

        assertEquals("Thunderbird", created.saved().getLabel());
        assertTrue(created.rawDisplay().matches("^(?:[A-Za-z0-9]{4}-){4}[A-Za-z0-9]{4}$"));
        String stripped = AuthService.normalizeAppPassword(created.rawDisplay());
        assertEquals(20, stripped.length());
        assertEquals("enc(" + stripped + ")", created.saved().getPasswordHash());

        when(appPasswords.findByIdAndUserId(existing.getId(), USER_ID)).thenReturn(Optional.of(existing));
        service.deleteAppPassword(USER_ID, existing.getId());
        verify(appPasswords).delete(existing);
    }

    @Test
    void appPasswordCreateAndDeleteRejectMissingEntities() {
        when(users.findById(USER_ID)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.createAppPassword(USER_ID, "Mail"));

        UUID appId = UUID.randomUUID();
        when(appPasswords.findByIdAndUserId(appId, USER_ID)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.deleteAppPassword(USER_ID, appId)).status());
    }

    @Test
    void staticHelpersNormalizeAndHashDeterministically() {
        assertEquals("", AuthService.normalize(null));
        assertEquals("ivan petrov", AuthService.normalize("  Ivan Petrov  "));
        assertEquals("", AuthService.normalizeAppPassword(null));
        assertEquals("abcd1234", AuthService.normalizeAppPassword("ab-cd 12-34"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AuthService.sha256Hex("abc"));
        assertFalse(new LoginFlow(null, null).mfaRequired());
        assertTrue(new LoginFlow(null, "ticket").mfaRequired());
    }

    private void stubRefreshLookup(RefreshSession session) {
        when(sessions.findByTokenHash(AuthService.sha256Hex("raw"))).thenReturn(Optional.of(session));
    }

    private User user() {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("ivan");
        user.setEmail("ivan@example.test");
        user.setDisplayName("Ivan Petrov");
        user.setPasswordHash("hash");
        user.setSource(User.Source.LOCAL);
        user.setStatus(User.Status.ACTIVE);
        user.setDepartment("Sales");
        user.setQuotaBytes(5_000L);
        user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }

    private User totpUser() {
        User user = user();
        user.setTotpEnabled(true);
        user.setTotpSecretEnc("enc-secret");
        return user;
    }

    private LoginAttempt attempt(Instant createdAt) {
        LoginAttempt attempt = new LoginAttempt("ivan", IP, true, LoginAttempt.REASON_OK);
        attempt.setCreatedAt(createdAt);
        return attempt;
    }

    private RefreshSession refreshSession(Instant expiresAt, Instant revokedAt, boolean mfa) {
        RefreshSession session = new RefreshSession();
        session.setId(UUID.randomUUID());
        session.setUserId(USER_ID);
        session.setTokenHash(AuthService.sha256Hex("raw"));
        session.setExpiresAt(expiresAt);
        session.setRevokedAt(revokedAt);
        session.setMfaVerified(mfa);
        return session;
    }

    private static String currentTotp() {
        return totp(Instant.now().getEpochSecond() / 30);
    }

    private static String invalidTotp() {
        long counter = Instant.now().getEpochSecond() / 30;
        Set<String> valid = new HashSet<>();
        for (long drift = -2; drift <= 2; drift++) {
            valid.add(totp(counter + drift));
        }
        for (int candidate = 0; candidate < 1_000_000; candidate++) {
            String code = String.format("%06d", candidate);
            if (!valid.contains(code)) {
                return code;
            }
        }
        throw new IllegalStateException("no invalid TOTP candidate");
    }

    private static String totp(long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(TOTP_SECRET), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] decodeBase32(String value) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : value.replace("=", "").toUpperCase().toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                result.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return result.toByteArray();
    }
}
