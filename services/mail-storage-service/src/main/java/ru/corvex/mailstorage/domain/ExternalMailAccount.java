package ru.corvex.mailstorage.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Внешний почтовый ящик, подключённый к ящику Corvex: сбор/миграция входящей почты
 * (IMAP/POP3) и отправка от имени внешнего адреса (authenticated SMTP провайдера).
 * Пароль приложения хранится только как AES-256-GCM шифртекст (secret_enc) и никогда
 * не возвращается API/логам.
 */
@Entity
@Table(name = "external_mail_accounts")
@Getter
@Setter
@NoArgsConstructor
public class ExternalMailAccount {

    public enum Provider {MAIL_RU, YANDEX, GMAIL, OUTLOOK, CUSTOM}

    public enum Protocol {IMAP, POP3}

    public enum Security {SSL_TLS, STARTTLS, PLAIN}

    public enum AuthType {APP_PASSWORD, PASSWORD, OAUTH2}

    public enum OwnerKind {PERSONAL, SHARED_MAILBOX}

    public enum InitialSyncMode {NEW_ONLY, IMPORT_ALL}

    public enum SyncScope {INBOX_ONLY, ALL_FOLDERS}

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "created_by")
    private UUID createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_kind", nullable = false, length = 24)
    private OwnerKind ownerKind = OwnerKind.PERSONAL;

    @Column(nullable = false, length = 128)
    private String label;

    @Column(name = "email_address", nullable = false, length = 320)
    private String emailAddress;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Provider provider = Provider.CUSTOM;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 24)
    private AuthType authType = AuthType.APP_PASSWORD;

    @Enumerated(EnumType.STRING)
    @Column(name = "incoming_protocol", nullable = false, length = 8)
    private Protocol incomingProtocol = Protocol.IMAP;

    @Column(name = "incoming_host", nullable = false, length = 255)
    private String incomingHost;

    @Column(name = "incoming_port", nullable = false)
    private int incomingPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "incoming_security", nullable = false, length = 16)
    private Security incomingSecurity = Security.SSL_TLS;

    @Column(name = "incoming_username", nullable = false, length = 320)
    private String incomingUsername;

    @Column(name = "secret_enc", nullable = false, columnDefinition = "TEXT")
    private String secretEnc;

    @Column(name = "smtp_enabled", nullable = false)
    private boolean smtpEnabled = true;

    @Column(name = "smtp_host", length = 255)
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Enumerated(EnumType.STRING)
    @Column(name = "smtp_security", nullable = false, length = 16)
    private Security smtpSecurity = Security.SSL_TLS;

    @Column(name = "smtp_username", length = 320)
    private String smtpUsername;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_folder_id")
    private MailFolder targetFolder;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled = true;

    @Column(name = "send_enabled", nullable = false)
    private boolean sendEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "initial_sync_mode", nullable = false, length = 16)
    private InitialSyncMode initialSyncMode = InitialSyncMode.NEW_ONLY;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_scope", nullable = false, length = 16)
    private SyncScope syncScope = SyncScope.INBOX_ONLY;

    @Column(name = "poll_interval_seconds", nullable = false)
    private int pollIntervalSeconds = 300;

    @Column(name = "leave_on_server", nullable = false)
    private boolean leaveOnServer = true;

    @Column(name = "mark_seen_on_server", nullable = false)
    private boolean markSeenOnServer;

    @Column(name = "delete_after_fetch", nullable = false)
    private boolean deleteAfterFetch;

    @Column(name = "save_sent_on_server", nullable = false)
    private boolean saveSentOnServer = true;

    @Column(name = "sent_folder_name", length = 512)
    private String sentFolderName;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "next_sync_at")
    private Instant nextSyncAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "sync_lock_owner", length = 128)
    private String syncLockOwner;

    @Column(name = "sync_lock_until")
    private Instant syncLockUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (nextSyncAt == null) nextSyncAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
