package ru.corvex.auth.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Пароль приложения для внешних клиентов (Outlook/Thunderbird по IMAP/SMTP).
 * Значение показывается пользователю один раз при создании; хранится Argon2id-хэш
 * от формы без разделителей (дефисы/пробелы при вводе игнорируются).
 */
@Entity
@Table(name = "app_passwords")
@Getter
@Setter
@NoArgsConstructor
public class AppPassword {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(name = "password_hash", nullable = false, length = 256)
    private String passwordHash;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
