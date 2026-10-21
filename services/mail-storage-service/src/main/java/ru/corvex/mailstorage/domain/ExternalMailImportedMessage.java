package ru.corvex.mailstorage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Дедупликация и маршрутизация одного письма внешнего ящика. Запись создаётся до публикации
 * в обычный конвейер spam → AV → storage: по raw_ref финальная доставка узнаёт целевую папку,
 * исходные IMAP-флаги и internal-date, а повторный сбор не создаёт дубль.
 */
@Entity
@Table(name = "external_mail_imported_messages")
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailImportedMessage {

    public enum Status {QUEUED, DELIVERED, FAILED, SKIPPED_INITIAL, SKIPPED_TOO_LARGE}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private ExternalMailAccount account;

    @Column(name = "remote_folder", nullable = false, length = 1024)
    private String remoteFolder;

    @Column(name = "remote_uid", nullable = false, length = 512)
    private String remoteUid;

    @Column(name = "uid_validity")
    private Long UIDValidity;

    @Column(name = "message_id_header", length = 998)
    private String messageIdHeader;

    @Column(length = 64)
    private String sha256;

    @Column(name = "raw_ref", length = 64)
    private String rawRef;

    @Column(name = "corvex_message_id")
    private UUID corvexMessageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_folder_id")
    private MailFolder targetFolder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.QUEUED;

    private boolean seen;
    private boolean answered;
    private boolean flagged;
    private boolean draft;
    private boolean deleted;

    @Column(name = "preserve_target_folder", nullable = false)
    private boolean preserveTargetFolder;

    @Column(name = "source_internal_date")
    private Instant sourceInternalDate;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (importedAt == null) importedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
