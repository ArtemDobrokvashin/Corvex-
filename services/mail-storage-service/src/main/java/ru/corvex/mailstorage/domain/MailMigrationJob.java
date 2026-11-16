package ru.corvex.mailstorage.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Задание переноса почты из внешнего ящика. Хранит, что именно планировалось перенести
 * (оценка до запуска), чем закончилось и к какому запуску сбора относится.
 *
 * <p>Оценка и факт разделены намеренно: экран «Перенос» показывает, помещается ли архив
 * в квоту до старта, а после завершения — сколько перенесено, пропущено и с какой ошибкой.
 */
@Entity
@Table(name = "mail_migration_jobs")
@Getter
@Setter
@NoArgsConstructor
public class MailMigrationJob {

    /** PAUSED и CANCELLED отличаются: паузу можно продолжить, отмену — нет. */
    public enum Status {QUEUED, RUNNING, PAUSED, DONE, PARTIAL, FAILED, CANCELLED}

    /** FULL — весь архив; DELTA — добор писем, пришедших после первого прохода. */
    public enum Mode {FULL, DELTA}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private ExternalMailAccount account;

    @Column(name = "created_by")
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Mode mode = Mode.FULL;

    /** Переносить ли дубли Gmail: All Mail, Important, Starred. По умолчанию нет. */
    @Column(name = "include_all_mail", nullable = false)
    private boolean includeAllMail;

    /** Дополнительные папки, которые пользователь исключил, по одной в строке. */
    @Column(name = "skip_folders", columnDefinition = "TEXT")
    private String skipFolders;

    @Column(name = "estimated_messages", nullable = false)
    private long estimatedMessages;

    @Column(name = "estimated_bytes", nullable = false)
    private long estimatedBytes;

    @Column(name = "run_id")
    private UUID runId;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean active() {
        return status == Status.QUEUED || status == Status.RUNNING || status == Status.PAUSED;
    }

    public boolean finished() {
        return status == Status.DONE || status == Status.PARTIAL
                || status == Status.FAILED || status == Status.CANCELLED;
    }
}
