package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Приглашение на перенос из админского CSV. Пароля здесь нет и быть не может:
 * источник и логин — подсказка сотруднику, пароль приложения вводит владелец ящика.
 */
@Entity
@Table(name = "mail_migration_invitations")
@Getter
@Setter
@NoArgsConstructor
public class MailMigrationInvitation {

    public enum Status {PENDING, STARTED, DONE, DISMISSED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "source_host", length = 255)
    private String sourceHost;

    @Column(name = "source_login", length = 320)
    private String sourceLogin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.PENDING;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
