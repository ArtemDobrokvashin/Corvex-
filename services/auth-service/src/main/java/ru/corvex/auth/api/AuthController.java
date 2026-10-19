package ru.corvex.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.auth.api.dto.AuthDtos.AppPasswordCreated;
import ru.corvex.auth.api.dto.AuthDtos.AppPasswordView;
import ru.corvex.auth.api.dto.AuthDtos.ChangePasswordRequest;
import ru.corvex.auth.api.dto.AuthDtos.CreateAppPasswordRequest;
import ru.corvex.auth.api.dto.AuthDtos.LoginRequest;
import ru.corvex.auth.api.dto.AuthDtos.MfaRequiredResponse;
import ru.corvex.auth.api.dto.AuthDtos.MfaVerifyRequest;
import ru.corvex.auth.api.dto.AuthDtos.TokenResponse;
import ru.corvex.auth.api.dto.AuthDtos.TotpCodeRequest;
import ru.corvex.auth.api.dto.AuthDtos.TotpSetupResponse;
import ru.corvex.auth.api.dto.AuthDtos.UserView;
import ru.corvex.auth.service.AuthService;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Публичный API входа — зеркало docs/api/auth-api.yaml. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Имя refresh-cookie — константа: используется одновременно в Set-Cookie и @CookieValue. */
    static final String REFRESH_COOKIE = "corvex_refresh";

    private final AuthService auth;
    private final boolean refreshCookieSecure;
    private final Duration refreshTtl;

    public AuthController(AuthService auth,
                          @Value("${corvex.security.refresh-cookie-secure:true}") boolean refreshCookieSecure,
                          @Value("${corvex.security.refresh-token-ttl:P30D}") Duration refreshTtl) {
        this.auth = auth;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshTtl = refreshTtl;
    }

    @PostMapping("/login")
    ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        AuthService.LoginFlow flow = auth.login(req.username(), req.password(), clientIp(http), userAgent(http));
        if (flow.mfaRequired()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new MfaRequiredResponse(true, flow.mfaTicket()));
        }
        return withRefreshCookie(flow.issued());
    }

    @PostMapping("/mfa/verify")
    ResponseEntity<TokenResponse> mfaVerify(@Valid @RequestBody MfaVerifyRequest req, HttpServletRequest http) {
        return withRefreshCookie(auth.verifyMfa(req.mfaTicket(), req.code(), clientIp(http), userAgent(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<TokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshRaw,
            HttpServletRequest http) {
        return withRefreshCookie(auth.refresh(refreshRaw, clientIp(http), userAgent(http)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshRaw,
            @AuthenticationPrincipal Jwt jwt) {
        auth.logout(refreshRaw,
                jwt == null ? null : jwt.getId(),
                jwt == null ? null : jwt.getExpiresAt());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedCookie().toString())
                .build();
    }

    @GetMapping("/me")
    UserView me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(userId(jwt), jwt.getClaimAsStringList("groups"), jwt.getClaimAsStringList("permissions"));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal Jwt jwt,
                        @Valid @RequestBody ChangePasswordRequest req,
                        @CookieValue(name = REFRESH_COOKIE, required = false) String refreshRaw) {
        auth.changePassword(userId(jwt), req.currentPassword(), req.newPassword(), refreshRaw);
    }

    @PostMapping("/mfa/totp/setup")
    TotpSetupResponse totpSetup(@AuthenticationPrincipal Jwt jwt) {
        AuthService.TotpSetup setup = auth.totpSetup(userId(jwt));
        return new TotpSetupResponse(setup.secret(), setup.otpauthUri());
    }

    @PostMapping("/mfa/totp/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void totpConfirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TotpCodeRequest req) {
        auth.totpConfirm(userId(jwt), req.code());
    }

    @PostMapping("/mfa/totp/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void totpDisable(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TotpCodeRequest req) {
        auth.totpDisable(userId(jwt), req.code());
    }

    @GetMapping("/app-passwords")
    List<AppPasswordView> appPasswords(@AuthenticationPrincipal Jwt jwt) {
        return auth.listAppPasswords(userId(jwt)).stream().map(AppPasswordView::of).toList();
    }

    @PostMapping("/app-passwords")
    ResponseEntity<AppPasswordCreated> createAppPassword(@AuthenticationPrincipal Jwt jwt,
                                                          @Valid @RequestBody CreateAppPasswordRequest req) {
        AuthService.CreatedAppPassword created = auth.createAppPassword(userId(jwt), req.label());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AppPasswordCreated(created.saved().getId(), created.rawDisplay()));
    }

    @DeleteMapping("/app-passwords/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAppPassword(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        auth.deleteAppPassword(userId(jwt), id);
    }

    // ---------- helpers ----------

    private ResponseEntity<TokenResponse> withRefreshCookie(AuthService.IssuedSession issued) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, issued.refreshTokenRaw())
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth") // cookie уходит только в auth-эндпоинты
                .maxAge(refreshTtl)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(TokenResponse.bearer(issued.accessToken(), issued.expiresInSeconds()));
    }

    private ResponseCookie clearedCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(0)
                .build();
    }

    private static UUID userId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw ApiException.unauthorized("Нет аутентификации");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("Некорректный токен");
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest req) {
        String ua = req.getHeader("User-Agent");
        return ua == null ? null : ua.substring(0, Math.min(ua.length(), 512));
    }
}
