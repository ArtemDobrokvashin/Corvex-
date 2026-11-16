package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Очередь напоминаний с защитой от двойной отправки.
 *
 * <p>Строка появляется до отправки, а не после: перезапуск сервиса не должен
 * терять напоминание, а два экземпляра не должны отправить одно и то же.
 * Гарантия доставки — at-least-once: сбой между письмом и отметкой {@code SENT}
 * даёт повтор, но не потерю.
 */
@Entity
@Table(name = "calendar_reminder_deliveries")
@Getter
@Setter
@NoArgsConstructor
public class CalendarReminderDelivery {

    public enum Status {
        PENDING, SENDING, SENT, FAILED, CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** Начало конкретного вхождения; уникально вместе с event_id. */
    @Column(name = "occurrence_at", nullable = false)
    private Instant occurrenceAt;

    /** Момент, когда напоминание должно уйти: occurrence_at минус reminder_minutes. */
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(nullable = false, length = 12)
    private String status = Status.PENDING.name();

    @Column(nullable = false)
    private int attempts;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
