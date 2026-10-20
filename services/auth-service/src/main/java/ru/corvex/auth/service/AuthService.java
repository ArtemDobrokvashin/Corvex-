package ru.corvex.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.corvex.auth.security.PasswordPolicy;
import ru.corvex.auth.security.TokenService;
import ru.corvex.auth.security.TotpSecretCipher;
import ru.corvex.auth.security.TotpService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Вход (пароль Argon2id + lockout + опционально TOTP), выпуск/ротация токенов,
 * профиль, смена пароля, TOTP и пароли приложений. AD/LDAP — этап 5: здесь
 * обрабатываются локальные учётки (source=LOCAL); у AD-записей passwordHash=null,
 * и вход по паролю для них честно отклоняется до этапа 5.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String MFA_KEY_PREFIX = "mfa:ticket:";
    private static final Duration MFA_TTL = Duration.ofMinutes(5);
    private static final int MFA_MAX_ATTEMPTS = 5;
    private static final String APP_PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final LoginAttemptRepository attempts;
    private final AppPasswordRepository appPasswords;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AdminAccessClient adminAccess;
    private final LoginAttemptRecorder attemptRecorder;
    private final SessionRevoker sessionRevoker;
    private final TotpSecretCipher totpCipher;
    private final StringRedisTemplate redis;
    private final Duration refreshTtl;
    private final int lockoutMaxFailures;
    private final Duration lockoutWindow;
    private final String dummyHash;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users,
                       RefreshSessionRepository sessions,
                       LoginAttemptRepository attempts,
                       AppPasswordRepository appPasswords,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AdminAccessClient adminAccess,
                       LoginAttemptRecorder attemptRecorder,
                       SessionRevoker sessionRevoker,
                       TotpSecretCipher totpCipher,
                       StringRedisTemplate redis,
                       @Value("${corvex.security.refresh-token-ttl:P30D}") Duration refreshTtl,
                       @Value("${corvex.security.lockout.max-failures:5}") int lockoutMaxFailures,
                       @Value("${corvex.security.lockout.window:PT15M}") Duration lockoutWindow) {
        this.users = users;
        this.sessions = sessions;
        this.attempts = attempts;
        this.appPasswords = appPasswords;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.adminAccess = adminAccess;
        this.attemptRecorder = attemptRecorder;
        this.sessionRevoker = sessionRevoker;
        this.totpCipher = totpCipher;
        this.redis = redis;
        this.refreshTtl = refreshTtl;
        this.lockoutMaxFailures = lockoutMaxFailures;
        this.lockoutWindow = lockoutWindow;
        // Хэш-«заглушка»: проверка пароля несуществующего пользователя занимает столько же
        // времени, что и существующего, — нельзя перебрать логины по времени ответа.
        this.dummyHash = passwordEncoder.encode("corvex-nonexistent-user-dummy");
    }

    public record IssuedSession(String accessToken, long expiresInSeconds, String refreshTokenRaw, UUID userId) {
    }

    public record LoginFlow(IssuedSession issued, String mfaTicket) {
        public boolean mfaRequired() {
            return mfaTicket != null;
        }
    }

    // ---------- Вход ----------

    @Transactional
    public LoginFlow login(String loginRaw, String password, String ip, String userAgent) {
        String login = normalize(loginRaw);
        assertNotLocked(login, ip);

        User user = users.findByLogin(login).orElse(null);
        boolean passwordOk;
        if (user != null && user.getPasswordHash() != null) {
            passwordOk = passwordEncoder.matches(password, user.getPasswordHash());
        } else {
            passwordEncoder.matches(password, dummyHash); // выравнивание времени ответа
            passwordOk = false;
        }
        if (!passwordOk || user == null) {
            attemptRecorder.record(login, ip, false, LoginAttempt.REASON_BAD_CREDENTIALS);
            throw ApiException.unauthorized("Неверный логин или пароль");
        }
        if (user.getStatus() != User.Status.ACTIVE) {
            attemptRecorder.record(login, ip, false, LoginAttempt.REASON_ACCOUNT_NOT_ACTIVE);
            throw ApiException.forbidden("Учётная запись заблокирована или отключена. Обратитесь к администратору");
        }
        if (user.isTotpEnabled()) {
            String ticket = randomToken();
            try {
                redis.opsForValue().set(MFA_KEY_PREFIX + ticket, user.getId() + ":0", MFA_TTL);
            } catch (RuntimeException e) {
                log.error("Redis unavailable while creating MFA ticket", e);
                throw ApiException.serviceUnavailable("Второй фактор временно недоступен, попробуйте позже");
            }
            return new LoginFlow(null, ticket);
        }
        return new LoginFlow(issueSession(user, ip, userAgent, false, true), null);
    }

    @Transactional
    public IssuedSession verifyMfa(String ticket, String code, String ip, String userAgent) {
        String key = MFA_KEY_PREFIX + ticket;
        String value;
        try {
            value = redis.opsForValue().get(key);
        } catch (RuntimeException e) {
            throw ApiException.serviceUnavailable("Второй фактор временно недоступен, попробуйте позже");
        }
        if (value == null) {
            throw ApiException.unauthorized("Сессия входа истекла, войдите заново");
        }
        String[] parts = value.split(":");
        UUID userId = UUID.fromString(parts[0]);
        int tries = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Сессия входа истекла, войдите заново"));

        boolean ok = user.getTotpSecretEnc() != null
                && TotpService.verify(totpCipher.decrypt(user.getTotpSecretEnc()), code, Instant.now());
        if (!ok) {
            attemptRecorder.record(user.getUsername(), ip, false, LoginAttempt.REASON_BAD_TOTP);
            if (tries + 1 >= MFA_MAX_ATTEMPTS) {
                redis.delete(key);
            } else {
                redis.opsForValue().set(key, userId + ":" + (tries + 1), MFA_TTL);
            }
            throw ApiException.unauthorized("Неверный код подтверждения");
        }
        redis.delete(key);
        if (user.getStatus() != User.Status.ACTIVE) {
            throw ApiException.forbidden("Учётная запись заблокирована или отключена");
        }
        return issueSession(user, ip, userAgent, true, true);
    }

    private void assertNotLocked(String login, String ip) {
        Instant windowStart = Instant.now().minus(lockoutWindow);
        Instant since = attempts.findFirstByUsernameIgnoreCaseAndSuccessTrueOrderByCreatedAtDesc(login)
                .map(a -> a.getCreatedAt().isAfter(windowStart) ? a.getCreatedAt() : windowStart)
                .orElse(windowStart);
        long failures = attempts.countFailuresSince(login, since);
        if (failures >= lockoutMaxFailures) {
            attemptRecorder.record(login, ip, false, LoginAttempt.REASON_LOCKED);
            throw ApiException.locked("Слишком много неверных попыток. Повторите через "
                    + lockoutWindow.toMinutes() + " минут");
        }
    }

    // ---------- Сессии / refresh / logout ----------

    @Transactional
    public IssuedSession refresh(String rawToken, String ip, String userAgent) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized("Нет refresh-токена");
        }
        RefreshSession session = sessions.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> ApiException.unauthorized("Refresh-токен недействителен"));
        Instant now = Instant.now();
        if (session.getRevokedAt() != null) {
            // Предъявлен уже отозванный токен: вероятная кража — гасим все сессии пользователя.
            log.warn("Revoked refresh token reuse detected for user {} — revoking all sessions", session.getUserId());
            sessionRevoker.revokeAll(session.getUserId());
            throw ApiException.unauthorized("Refresh-токен недействителен");
        }
        if (!session.getExpiresAt().isAfter(now)) {
            throw ApiException.unauthorized("Refresh-токен истёк");
        }
        User user = users.findById(session.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("Пользователь не существует"));
        if (user.getStatus() != User.Status.ACTIVE) {
            sessionRevoker.revokeAll(user.getId());
            throw ApiException.unauthorized("Учётная запись недоступна");
        }
        session.setRevokedAt(now); // ротация: старый токен умирает в момент выпуска нового
        sessions.save(session);
        return issueSession(user, ip, userAgent, session.isMfaVerified(), false);
    }

    public void logout(String rawToken, String accessJti, Instant accessExpiresAt) {
        if (rawToken != null && !rawToken.isBlank()) {
            sessions.findByTokenHash(sha256Hex(rawToken)).ifPresent(s -> {
                if (s.getRevokedAt() == null) {
                    s.setRevokedAt(Instant.now());
                    sessions.save(s);
                }
            });
        }
        if (accessJti != null && accessExpiresAt != null) {
            Duration ttl = Duration.between(Instant.now(), accessExpiresAt);
            if (!ttl.isNegative() && !ttl.isZero()) {
                try {
                    // Blacklist для будущих проверок на gateway (этап 5, hardening). До его включения
                    // access-токен живёт не дольше своих 15 минут — это зафиксировано в TokenService.
                    redis.opsForValue().set("jwt:blacklist:" + accessJti, "1", ttl);
                } catch (RuntimeException e) {
                    log.warn("Cannot write jti blacklist (Redis down): {}", e.getMessage());
                }
            }
        }
    }

    private IssuedSession issueSession(User user, String ip, String userAgent,
                                       boolean mfaVerified, boolean recordLogin) {
        AccessInfo access = adminAccess.fetch(user.getId()).orElseGet(AccessInfo::empty);
        TokenService.AccessToken accessToken = tokenService.issueAccessToken(
                user.getId(), user.getUsername(), access.groups(), access.permissions(), mfaVerified);
        String raw = randomToken();
        RefreshSession session = new RefreshSession();
        session.setUserId(user.getId());
        session.setTokenHash(sha256Hex(raw));
        session.setUserAgent(userAgent);
        session.setIpAddress(ip);
        session.setMfaVerified(mfaVerified);
        session.setExpiresAt(Instant.now().plus(refreshTtl));
        sessions.save(session);
        if (recordLogin) {
            user.setLastLoginAt(Instant.now());
            users.save(user);
            attemptRecorder.record(user.getUsername(), ip, true, LoginAttempt.REASON_OK);
        }
        long expiresIn = Math.max(0, Duration.between(Instant.now(), accessToken.expiresAt()).getSeconds());
        return new IssuedSession(accessToken.value(), expiresIn, raw, user.getId());
    }

    // ---------- Профиль / пароль ----------

    @Transactional(readOnly = true)
    public UserView me(UUID userId, List<String> jwtGroupsFallback, List<String> jwtPermissionsFallback) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Пользователь не существует"));
        AccessInfo access = adminAccess.fetch(userId)
                .orElseGet(() -> new AccessInfo(
                        jwtGroupsFallback == null ? List.of() : jwtGroupsFallback,
                        jwtPermissionsFallback == null ? List.of() : jwtPermissionsFallback));
        return UserView.of(user, access.groups(), access.permissions());
    }

    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, String currentRefreshRaw) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Пользователь не существует"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.unauthorized("Текущий пароль неверен");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.badRequest("Новый пароль должен отличаться от текущего");
        }
        String policyError = PasswordPolicy.validate(newPassword);
        if (policyError != null) {
            throw ApiException.unprocessable(policyError);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        users.save(user);
        // Все остальные устройства разлогиниваются; текущее (по его refresh-cookie) остаётся.
        sessionRevoker.revokeAllExcept(userId,
                currentRefreshRaw == null || currentRefreshRaw.isBlank() ? null : sha256Hex(currentRefreshRaw));
    }

    // ---------- TOTP ----------

    public record TotpSetup(String secret, String otpauthUri) {
    }

    @Transactional
    public TotpSetup totpSetup(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Пользователь не существует"));
        if (user.isTotpEnabled()) {
            // Иначе украденный access-токен мог бы одной setup-операцией выключить 2FA:
            // сначала требуется штатный disable с действующим TOTP-кодом.
            throw ApiException.badRequest("2FA уже включена. Сначала отключите её текущим кодом");
        }
        String secret = TotpService.generateSecret();
        user.setTotpSecretEnc(totpCipher.encrypt(secret));
        user.setTotpEnabled(false); // включится только после confirm первым кодом
        users.save(user);
        String label = URLEncoder.encode("Corvex:" + user.getUsername(), StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=Corvex&algorithm=SHA1&digits=6&period=30";
        return new TotpSetup(secret, uri);
    }

    @Transactional
    public void totpConfirm(UUID userId, String code) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Пользователь не существует"));
        if (user.getTotpSecretEnc() == null) {
            throw ApiException.badRequest("Сначала выполните настройку 2FA (totp/setup)");
        }
        if (!TotpService.verify(totpCipher.decrypt(user.getTotpSecretEnc()), code, Instant.now())) {
            throw ApiException.unauthorized("Неверный код подтверждения");
        }
        user.setTotpEnabled(true);
        users.save(user);
    }

    @Transactional
    public void totpDisable(UUID userId, String code) {
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Пользователь не существует"));
        if (!user.isTotpEnabled() || user.getTotpSecretEnc() == null) {
            throw ApiException.badRequest("2FA не включена");
        }
        if (!TotpService.verify(totpCipher.decrypt(user.getTotpSecretEnc()), code, Instant.now())) {
            throw ApiException.unauthorized("Неверный код подтверждения");
        }
        user.setTotpEnabled(false);
        user.setTotpSecretEnc(null);
        users.save(user);
    }

    // ---------- Пароли приложений ----------

    public record CreatedAppPassword(AppPassword saved, String rawDisplay) {
    }

    @Transactional(readOnly = true)
    public List<AppPassword> listAppPasswords(UUID userId) {
        return appPasswords.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public CreatedAppPassword createAppPassword(UUID userId, String label) {
        users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Пользователь не существует"));
        String stripped = generateAppPasswordRaw();
        AppPassword p = new AppPassword();
        p.setUserId(userId);
        p.setLabel(label.trim());
        p.setPasswordHash(passwordEncoder.encode(stripped));
        AppPassword saved = appPasswords.save(p);
        return new CreatedAppPassword(saved, groupAppPassword(stripped));
    }

    @Transactional
    public void deleteAppPassword(UUID userId, UUID appPasswordId) {
        AppPassword p = appPasswords.findByIdAndUserId(appPasswordId, userId)
                .orElseThrow(() -> ApiException.notFound("Пароль приложения не найден"));
        appPasswords.delete(p);
    }

    private String generateAppPasswordRaw() {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(APP_PASSWORD_ALPHABET.charAt(random.nextInt(APP_PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String groupAppPassword(String stripped) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < stripped.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(stripped.charAt(i));
        }
        return sb.toString();
    }

    /** Нормализация ввода пароля приложения: дефисы и пробелы игнорируются. */
    public static String normalizeAppPassword(String input) {
        return input == null ? "" : input.replace("-", "").replace(" ", "");
    }

    // ---------- Утилиты ----------

    public static String normalize(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
