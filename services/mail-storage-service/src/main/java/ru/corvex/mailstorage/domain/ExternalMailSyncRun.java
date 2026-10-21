package ru.corvex.mailstorage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Один запуск сбора/миграции внешнего ящика: статус для UI и эксплуатационная история. */
@Entity
@Table(name = "external_mail_sync_runs")
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailSyncRun {

    public enum Trigger {SCHEDULED, MANUAL, MIGRATION}

    public enum Status {RUNNING, SUCCESS, PARTIAL, FAILED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private ExternalMailAccount account;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 24)
    private Trigger triggerType = Trigger.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.RUNNING;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "folders_count", nullable = false)
    private int foldersCount;

    @Column(name = "fetched_count", nullable = false)
    private int fetchedCount;

    @Column(name = "queued_count", nullable = false)
    private int queuedCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(columnDefinition = "TEXT")
    private String error;

    @PrePersist
    void prePersist() {
        if (startedAt == null) startedAt = Instant.now();
    }
}
