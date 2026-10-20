package ru.corvex.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Попытка входа: основа lockout-защиты от брутфорса и статистика входов. */
@Entity
@Table(name = "login_attempts")
@Getter
@Setter
@NoArgsConstructor
public class LoginAttempt {

    public static final String REASON_OK = "OK";
    public static final String REASON_BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String REASON_LOCKED = "LOCKED";
    public static final String REASON_ACCOUNT_NOT_ACTIVE = "ACCOUNT_NOT_ACTIVE";
    public static final String REASON_BAD_TOTP = "BAD_TOTP";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(nullable = false)
    private boolean success;

    @Column(length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LoginAttempt(String username, String ipAddress, boolean success, String reason) {
        this.username = username;
        this.ipAddress = ipAddress;
        this.success = success;
        this.reason = reason;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
