package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие календаря. Хранится момент времени (UTC) плюс IANA-зона: без зоны
 * событие «в 09:00» после перехода на летнее время сдвинулось бы.
 *
 * <p>Удаление мягкое: другой клиент или поставленное напоминание может ещё
 * ссылаться на строку, а восстановление истории дешевле, чем её отсутствие.
 */
@Entity
@Table(name = "calendar_events")
@Getter
@Setter
@NoArgsConstructor
public class CalendarEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description = "";

    @Column(nullable = false, length = 200)
    private String location = "";

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(nullable = false, length = 8)
    private String priority = "medium";

    @Column(name = "busy_status", nullable = false, length = 12)
    private String busyStatus = "busy";

    @Column(nullable = false, length = 12)
    private String recurrence = "none";

    @Column(name = "reminder_minutes")
    private Integer reminderMinutes;

    /** По одному адресу в строке; список ограничен и всегда читается целиком. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String attendees = "";

    @Column(name = "source_kind", nullable = false, length = 8)
    private String sourceKind = "NONE";

    @Column(name = "source_mail_id")
    private UUID sourceMailId;

    @Column(name = "source_mail_subject", length = 512)
    private String sourceMailSubject;

    @Column(name = "source_mail_sender", length = 512)
    private String sourceMailSender;

    /** Растёт на каждом изменении: клиент присылает её обратно, чтобы не затереть чужую правку. */
    @Column(nullable = false)
    private long revision = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
