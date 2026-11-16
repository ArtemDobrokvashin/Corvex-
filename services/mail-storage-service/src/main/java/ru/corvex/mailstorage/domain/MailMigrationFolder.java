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
 * Одна папка внутри задания переноса. Строка создаётся на этапе preview, поэтому отчёт
 * показывает и папки с нулём перенесённых писем — иначе «куда делся Архив» остаётся без ответа.
 */
@Entity
@Table(name = "mail_migration_folders")
@Getter
@Setter
@NoArgsConstructor
public class MailMigrationFolder {

    public enum State {PENDING, RUNNING, DONE, SKIPPED, FAILED}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private MailMigrationJob job;

    @Column(name = "remote_folder", nullable = false, length = 1024)
    private String remoteFolder;

    /** RFC 6154 special-use, если сервер его сообщил: SENT, DRAFTS, TRASH, SPAM, ARCHIVE. */
    @Column(name = "special_use", length = 24)
    private String specialUse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private State state = State.PENDING;

    @Column(name = "estimated_messages", nullable = false)
    private int estimatedMessages;

    @Column(name = "estimated_bytes", nullable = false)
    private long estimatedBytes;

    @Column(name = "migrated_messages", nullable = false)
    private int migratedMessages;

    @Column(name = "skipped_messages", nullable = false)
    private int skippedMessages;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
