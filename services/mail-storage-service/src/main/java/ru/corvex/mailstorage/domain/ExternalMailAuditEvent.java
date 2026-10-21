package ru.corvex.mailstorage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Аудит действий с внешними ящиками. Details никогда не содержат пароль/токен. */
@Entity
@Table(name = "external_mail_audit_events")
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id")
    private ExternalMailAccount account;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 2000)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
