package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Показывали ли сотруднику предложение переноса и чем оно закончилось.
 *
 * <p>DONE здесь — «перенос состоялся или идёт»: состояние выводится из заданий, а строка
 * хранит только решения пользователя («позже», «мне нечего переносить»).
 */
@Entity
@Table(name = "user_onboarding")
@Getter
@Setter
@NoArgsConstructor
public class UserOnboarding {

    public enum MigrationState {PENDING, DISMISSED, NOT_NEEDED, DONE}

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "migration_state", nullable = false, length = 24)
    private MigrationState migrationState = MigrationState.PENDING;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "not_needed_at")
    private Instant notNeededAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
