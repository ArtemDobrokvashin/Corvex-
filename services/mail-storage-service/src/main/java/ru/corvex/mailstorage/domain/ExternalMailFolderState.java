package ru.corvex.mailstorage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Курсор синхронизации одной удалённой папки IMAP (UIDVALIDITY + последний UID) или POP3 INBOX. */
@Entity
@Table(name = "external_mail_folder_states",
        uniqueConstraints = @UniqueConstraint(name = "uq_external_folder_state", columnNames = {"account_id", "remote_folder"}))
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailFolderState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private ExternalMailAccount account;

    @Column(name = "remote_folder", nullable = false, length = 1024)
    private String remoteFolder;

    @Column(name = "remote_delimiter", length = 8)
    private String remoteDelimiter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_folder_id")
    private MailFolder targetFolder;

    @Column(name = "uid_validity")
    private Long UIDValidity;

    @Column(name = "last_seen_uid", nullable = false)
    private long lastSeenUid;

    @Column(name = "initial_snapshot_done", nullable = false)
    private boolean initialSnapshotDone;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
