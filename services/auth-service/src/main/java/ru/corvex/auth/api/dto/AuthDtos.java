package ru.corvex.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import ru.corvex.auth.domain.AppPassword;
import ru.corvex.auth.domain.User;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Все DTO auth-service одним файлом — зеркало docs/api/auth-api.yaml (+ internal API для admin-service). */
public final class AuthDtos {

    private AuthDtos() {
    }

    // --- Публичный API /api/v1/auth ---

    public record LoginRequest(
            @NotBlank(message = "укажите логин") String username,
            @NotBlank(message = "укажите пароль") String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
        public static TokenResponse bearer(String accessToken, long expiresInSeconds) {
            return new TokenResponse(accessToken, "Bearer", expiresInSeconds);
        }
    }

    public record MfaRequiredResponse(boolean mfaRequired, String mfaTicket) {
    }

    public record MfaVerifyRequest(
            @NotBlank(message = "нет mfaTicket") String mfaTicket,
            @Pattern(regexp = "[0-9]{6}", message = "код — 6 цифр") String code) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank String newPassword) {
    }

    public record TotpSetupResponse(String secret, String otpauthUri) {
    }

    public record TotpCodeRequest(@Pattern(regexp = "[0-9]{6}", message = "код — 6 цифр") String code) {
    }

    public record CreateAppPasswordRequest(
            @NotBlank(message = "укажите название") @Size(max = 128) String label) {
    }

    public record AppPasswordView(UUID id, String label, Instant createdAt, Instant lastUsedAt) {
        public static AppPasswordView of(AppPassword p) {
            return new AppPasswordView(p.getId(), p.getLabel(), p.getCreatedAt(), p.getLastUsedAt());
        }
    }

    public record AppPasswordCreated(UUID id, String password) {
    }

    /** Профиль пользователя (публичный /me и внутренний вид для admin-service — одна форма). */
    public record UserView(
            UUID id,
            String username,
            String email,
            String displayName,
            String source,
            String status,
            String department,
            List<String> groups,
            List<String> permissions,
            long quotaBytes,
            long usedBytes,
            boolean mustChangePassword,
            boolean totpEnabled,
            Instant lastLoginAt,
            Instant createdAt) {

        public static UserView of(User u, List<String> groups, List<String> permissions) {
            return new UserView(
                    u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(),
                    u.getSource().name(), u.getStatus().name(), u.getDepartment(),
                    groups == null ? List.of() : groups,
                    permissions == null ? List.of() : permissions,
                    u.getQuotaBytes(),
                    0L, // usedBytes появится с почтовым хранилищем на этапе 2
                    u.isMustChangePassword(), u.isTotpEnabled(),
                    u.getLastLoginAt(), u.getCreatedAt());
        }
    }

    // --- Internal API /internal (только docker-сеть app, gateway не маршрутизирует) ---

    public record CreateUserRequest(
            @NotBlank @Size(min = 2, max = 64) String username,
            @NotBlank @Email(message = "некорректный e-mail") @Size(max = 320) String email,
            @NotBlank @Size(max = 256) String displayName,
            @NotBlank String password,
            @Size(max = 256) String department,
            @PositiveOrZero Long quotaBytes) {
    }

    public record PatchUserRequest(
            @Size(max = 256) String displayName,
            @Email @Size(max = 320) String email,
            @Size(max = 256) String department,
            @PositiveOrZero Long quotaBytes,
            String status) {
    }

    public record UserPage(List<UserView> content, int page, int size, long totalElements, int totalPages) {
    }

    public record VerifyPasswordRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** method: PASSWORD | APP_PASSWORD | null при отказе. */
    public record VerifyPasswordResponse(boolean valid, UUID userId, String method) {
    }

    public record StatsResponse(
            long totalUsers, long activeUsers, long blockedUsers, long disabledUsers,
            Long loginsLast24h, Long loginsLast7d, String department) {
    }
}
