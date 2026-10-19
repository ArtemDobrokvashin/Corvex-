package ru.corvex.admin.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** DTO admin-service — зеркало docs/api/admin-api.yaml (+ внутренний access для auth-service). */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record AdminUserView(
            UUID id, String username, String email, String displayName,
            String source, String status, String department,
            List<String> groups, long quotaBytes, long usedBytes,
            boolean mustChangePassword, boolean totpEnabled,
            Instant lastLoginAt, Instant createdAt) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 2, max = 64) String username,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 256) String displayName,
            @NotBlank String tempPassword,
            @Size(max = 256) String department,
            List<String> groupCodes,
            @PositiveOrZero Long quotaBytes) {
    }

    public record PatchUserRequest(
            @Size(max = 256) String displayName,
            @Email @Size(max = 320) String email,
            @Size(max = 256) String department,
            @PositiveOrZero Long quotaBytes,
            String status) {
    }

    public record SetGroupsRequest(@NotNull(message = "groupCodes обязателен (может быть пустым)") List<String> groupCodes) {
    }

    public record GroupView(
            String code, String name, boolean isSystem, boolean enabled,
            List<String> permissions, long memberCount) {
    }

    public record CreateGroupRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_\\-]{2,64}", message = "код группы: латиница, цифры, _ и -") String code,
            @NotBlank @Size(max = 256) String name,
            List<String> permissions) {
    }

    public record SetPermissionsRequest(@NotNull(message = "permissions обязателен (может быть пустым)") List<String> permissions) {
    }

    public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    public record AuditView(
            long id, UUID actorId, String actorName, String action,
            String entity, String entityId, Map<String, Object> details,
            String ipAddress, Instant createdAt) {
    }

    public record AccessView(List<String> groups, List<String> permissions) {
    }

    public record ServiceHealth(String name, String status, String version) {
    }

    public record HealthResponse(List<ServiceHealth> services, long diskFreeBytes, long mailQueueDepth) {
    }

    public record AnalyticsResponse(
            String scope, String department,
            long usersTotal, long usersActive, long usersBlocked, long usersDisabled,
            Long loginsLast24h, Long loginsLast7d, Instant generatedAt) {
    }

    public record BackupResponse(String status, String note) {
    }
}
