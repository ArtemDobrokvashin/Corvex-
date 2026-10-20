package ru.corvex.auth.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.auth.api.ApiException;
import ru.corvex.auth.api.dto.AuthDtos.CreateUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.PatchUserRequest;
import ru.corvex.auth.api.dto.AuthDtos.StatsResponse;
import ru.corvex.auth.api.dto.AuthDtos.UserPage;
import ru.corvex.auth.api.dto.AuthDtos.UserView;
import ru.corvex.auth.api.dto.AuthDtos.VerifyPasswordResponse;
import ru.corvex.auth.domain.AppPassword;
import ru.corvex.auth.domain.User;
import ru.corvex.auth.repository.AppPasswordRepository;
import ru.corvex.auth.repository.LoginAttemptRepository;
import ru.corvex.auth.repository.UserRepository;
import ru.corvex.auth.security.PasswordPolicy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Внутреннее управление учётными записями для admin-service и почтовых протоколов.
 * Auth остаётся единственным владельцем auth_db: админка никогда не пишет в неё напрямую.
 */
@Service
public class InternalUserService {

    private static final long DEFAULT_QUOTA_BYTES = 5368709120L;

    private final UserRepository users;
    private final AppPasswordRepository appPasswords;
    private final LoginAttemptRepository attempts;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevoker sessionRevoker;

    public InternalUserService(UserRepository users,
                               AppPasswordRepository appPasswords,
                               LoginAttemptRepository attempts,
                               PasswordEncoder passwordEncoder,
                               SessionRevoker sessionRevoker) {
        this.users = users;
        this.appPasswords = appPasswords;
        this.attempts = attempts;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevoker = sessionRevoker;
    }

    @Transactional
    public UserView create(CreateUserRequest req) {
        String username = AuthService.normalize(req.username());
        String email = AuthService.normalize(req.email());
        if (users.existsByUsernameIgnoreCase(username)) {
            throw ApiException.conflict("Логин уже занят: " + username);
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("E-mail уже занят: " + email);
        }
        String policyError = PasswordPolicy.validate(req.password());
        if (policyError != null) {
            throw ApiException.unprocessable(policyError);
        }
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setDisplayName(req.displayName().trim());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setSource(User.Source.LOCAL);
        u.setDepartment(req.department());
        u.setQuotaBytes(req.quotaBytes() == null ? DEFAULT_QUOTA_BYTES : req.quotaBytes());
        u.setStatus(User.Status.ACTIVE);
        u.setMustChangePassword(true); // временный пароль от админа меняется при первом входе
        return UserView.of(users.save(u), List.of(), List.of());
    }

    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return UserView.of(load(id), List.of(), List.of());
    }

    @Transactional(readOnly = true)
    public UserView getByUsername(String username) {
        User u = users.findByUsernameIgnoreCase(AuthService.normalize(username))
                .orElseThrow(() -> ApiException.notFound("Пользователь не найден: " + username));
        return UserView.of(u, List.of(), List.of());
    }

    /**
     * Разрешение почтового адресата в владельца ящика для этапа 2.
     * mail-storage-service не читает auth_db напрямую: по e-mail он получает только
     * id/status/quota активного пользователя и уже сам считает занятое место по mail_db.
     */
    @Transactional(readOnly = true)
    public UserView getByEmail(String email) {
        User u = users.findByEmailIgnoreCase(AuthService.normalize(email))
                .orElseThrow(() -> ApiException.notFound("Пользователь не найден: " + email));
        return UserView.of(u, List.of(), List.of());
    }

    @Transactional(readOnly = true)
    public UserPage list(String q, String status, String department, int page, int size) {
        User.Status st = parseStatus(status);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Page<User> result = users.search(
                q == null || q.isBlank() ? null : q.trim(),
                st,
                department == null || department.isBlank() ? null : department.trim(),
                PageRequest.of(Math.max(page, 0), safeSize, Sort.by("displayName").ascending()));
        return new UserPage(
                result.getContent().stream().map(u -> UserView.of(u, List.of(), List.of())).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public UserView patch(UUID id, PatchUserRequest req) {
        User u = load(id);
        if (req.displayName() != null && !req.displayName().isBlank()) {
            u.setDisplayName(req.displayName().trim());
        }
        if (req.email() != null && !req.email().isBlank()) {
            String email = AuthService.normalize(req.email());
            if (!email.equals(u.getEmail()) && users.existsByEmailIgnoreCase(email)) {
                throw ApiException.conflict("E-mail уже занят: " + email);
            }
            u.setEmail(email);
        }
        if (req.department() != null) {
            u.setDepartment(req.department().isBlank() ? null : req.department().trim());
        }
        if (req.quotaBytes() != null) {
            u.setQuotaBytes(req.quotaBytes());
        }
        if (req.status() != null) {
            User.Status st = parseStatus(req.status());
            u.setStatus(st);
            if (st != User.Status.ACTIVE) {
                sessionRevoker.revokeAll(u.getId());
            }
        }
        return UserView.of(users.save(u), List.of(), List.of());
    }

    @Transactional
    public void setStatus(UUID id, User.Status status) {
        User u = load(id);
        u.setStatus(status);
        users.save(u);
        if (status != User.Status.ACTIVE) {
            sessionRevoker.revokeAll(id);
        }
    }

    /**
     * Проверка пароля для почтовых протоколов (IMAP/SMTP, этап 3).
     * Семантика: сначала пароли приложений; основной пароль принимается, только
     * если у пользователя НЕ включён TOTP (иначе смысл 2FA теряется — нужен app-password).
     */
    @Transactional
    public VerifyPasswordResponse verifyPassword(String usernameRaw, String password) {
        User user = users.findByLogin(AuthService.normalize(usernameRaw)).orElse(null);
        if (user == null || user.getStatus() != User.Status.ACTIVE) {
            return new VerifyPasswordResponse(false, null, null);
        }
        String stripped = AuthService.normalizeAppPassword(password);
        for (AppPassword p : appPasswords.findAllByUserIdOrderByCreatedAtDesc(user.getId())) {
            if (passwordEncoder.matches(stripped, p.getPasswordHash())) {
                p.setLastUsedAt(Instant.now());
                appPasswords.save(p);
                return new VerifyPasswordResponse(true, user.getId(), "APP_PASSWORD");
            }
        }
        if (!user.isTotpEnabled() && user.getPasswordHash() != null
                && passwordEncoder.matches(password, user.getPasswordHash())) {
            return new VerifyPasswordResponse(true, user.getId(), "PASSWORD");
        }
        return new VerifyPasswordResponse(false, null, null);
    }

    @Transactional(readOnly = true)
    public StatsResponse stats(String department) {
        String dept = department == null || department.isBlank() ? null : department.trim();
        Long logins24 = null;
        Long logins7d = null;
        if (dept == null) {
            // Попытки входа не хранят подразделение — по подразделению статистика входов недоступна.
            logins24 = attempts.countBySuccessTrueAndCreatedAtAfter(Instant.now().minus(1, ChronoUnit.DAYS));
            logins7d = attempts.countBySuccessTrueAndCreatedAtAfter(Instant.now().minus(7, ChronoUnit.DAYS));
        }
        return new StatsResponse(
                users.countAllByDepartment(dept),
                users.countByStatusAndDepartment(User.Status.ACTIVE, dept),
                users.countByStatusAndDepartment(User.Status.BLOCKED, dept),
                users.countByStatusAndDepartment(User.Status.DISABLED, dept),
                logins24, logins7d, dept);
    }

    private User load(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("Пользователь не найден: " + id));
    }

    private static User.Status parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return User.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Неизвестный статус пользователя: " + status);
        }
    }
}
