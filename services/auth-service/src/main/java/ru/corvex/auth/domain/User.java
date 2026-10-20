package ru.corvex.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Учётная запись (auth_db.users). Группы и права пользователя здесь НЕ хранятся —
 * владелец RBAC — admin-service; auth запрашивает эффективные права при входе
 * (AdminAccessClient) и кладёт их в claims JWT.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

    public enum Source {LOCAL, AD_LDAP}

    public enum Status {ACTIVE, BLOCKED, DISABLED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Хранится в нижнем регистре (нормализация в сервисах) — поиск нечувствителен к регистру. */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 256)
    private String displayName;

    /** Argon2id-хэш (Spring Security). NULL у AD-пользователей (этап 5). */
    @Column(name = "password_hash", length = 256)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.LOCAL;

    @Column(name = "ad_object_guid", length = 64)
    private String adObjectGuid;

    @Column(length = 256)
    private String department;

    @Column(name = "manager_id")
    private UUID managerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.ACTIVE;

    /** Секрет TOTP, зашифрованный AES-GCM (TotpSecretCipher). До confirm — ожидающий секрет. */
    @Column(name = "totp_secret_enc", columnDefinition = "TEXT")
    private String totpSecretEnc;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    /** Временный пароль от админа должен быть сменён при первом входе (UI блокирует работу). */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "quota_bytes", nullable = false)
    private long quotaBytes = 5368709120L;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
