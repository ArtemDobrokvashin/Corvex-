package ru.corvex.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Refresh-сессия: в БД только SHA-256 хэш непрозрачного токена. Токен вращается
 * при каждом refresh; повторное предъявление уже отозванного токена трактуется
 * как кража — отзываются все сессии пользователя (AuthService.refresh).
 */
@Entity
@Table(name = "refresh_sessions")
@Getter
@Setter
@NoArgsConstructor
public class RefreshSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    private String tokenHash;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** Был ли второй фактор при входе — сохраняется в claim amr при refresh. */
    @Column(name = "mfa_verified", nullable = false)
    private boolean mfaVerified;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public boolean isActiveAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
