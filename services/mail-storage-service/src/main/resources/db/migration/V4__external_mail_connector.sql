-- External Mail Connector: подключение внешних ящиков (Mail.ru/Яндекс/Gmail/Outlook/custom),
-- сбор новой почты и миграция архива по IMAP/POP3, отправка от имени внешнего адреса через
-- authenticated SMTP провайдера. Секреты хранятся только AES-256-GCM шифртекстом.

CREATE TABLE external_mail_accounts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id              UUID NOT NULL,
    created_by            UUID,
    owner_kind            VARCHAR(24) NOT NULL DEFAULT 'PERSONAL',
    label                 VARCHAR(128) NOT NULL,
    email_address         VARCHAR(320) NOT NULL,
    display_name          VARCHAR(256),
    provider              VARCHAR(24) NOT NULL DEFAULT 'CUSTOM',
    auth_type             VARCHAR(24) NOT NULL DEFAULT 'APP_PASSWORD',

    incoming_protocol     VARCHAR(8) NOT NULL DEFAULT 'IMAP',
    incoming_host         VARCHAR(255) NOT NULL,
    incoming_port         INT NOT NULL,
    incoming_security     VARCHAR(16) NOT NULL DEFAULT 'SSL_TLS',
    incoming_username     VARCHAR(320) NOT NULL,
    secret_enc            TEXT NOT NULL,

    smtp_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    smtp_host             VARCHAR(255),
    smtp_port             INT,
    smtp_security         VARCHAR(16) NOT NULL DEFAULT 'SSL_TLS',
    smtp_username         VARCHAR(320),

    target_folder_id      UUID REFERENCES mail_folders(id) ON DELETE SET NULL,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    sync_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    send_enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    initial_sync_mode     VARCHAR(16) NOT NULL DEFAULT 'NEW_ONLY',
    sync_scope            VARCHAR(16) NOT NULL DEFAULT 'INBOX_ONLY',
    poll_interval_seconds INT NOT NULL DEFAULT 300 CHECK (poll_interval_seconds BETWEEN 60 AND 86400),
    leave_on_server       BOOLEAN NOT NULL DEFAULT TRUE,
    mark_seen_on_server   BOOLEAN NOT NULL DEFAULT FALSE,
    delete_after_fetch    BOOLEAN NOT NULL DEFAULT FALSE,
    save_sent_on_server   BOOLEAN NOT NULL DEFAULT TRUE,
    sent_folder_name      VARCHAR(512),

    last_sync_at          TIMESTAMPTZ,
    last_success_at       TIMESTAMPTZ,
    next_sync_at          TIMESTAMPTZ,
    last_error            TEXT,
    consecutive_failures  INT NOT NULL DEFAULT 0,
    sync_lock_owner       VARCHAR(128),
    sync_lock_until       TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_external_account_owner_email
    ON external_mail_accounts(owner_id, lower(email_address));
CREATE INDEX idx_external_account_due
    ON external_mail_accounts(enabled, sync_enabled, next_sync_at)
    WHERE enabled = TRUE AND sync_enabled = TRUE;
CREATE INDEX idx_external_account_owner ON external_mail_accounts(owner_id, created_at);

CREATE TABLE external_mail_folder_states (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID NOT NULL REFERENCES external_mail_accounts(id) ON DELETE CASCADE,
    remote_folder    VARCHAR(1024) NOT NULL,
    remote_delimiter VARCHAR(8),
    target_folder_id UUID REFERENCES mail_folders(id) ON DELETE SET NULL,
    uid_validity     BIGINT,
    last_seen_uid    BIGINT NOT NULL DEFAULT 0,
    initial_snapshot_done BOOLEAN NOT NULL DEFAULT FALSE,
    last_sync_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, remote_folder)
);
CREATE INDEX idx_external_folder_state_account ON external_mail_folder_states(account_id);

CREATE TABLE external_mail_imported_messages (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id            UUID NOT NULL REFERENCES external_mail_accounts(id) ON DELETE CASCADE,
    remote_folder         VARCHAR(1024) NOT NULL,
    remote_uid            VARCHAR(512) NOT NULL,
    uid_validity          BIGINT,
    message_id_header     VARCHAR(998),
    sha256                VARCHAR(64),
    raw_ref               VARCHAR(64),
    corvex_message_id     UUID,
    target_folder_id      UUID REFERENCES mail_folders(id) ON DELETE SET NULL,
    status                VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    seen                  BOOLEAN NOT NULL DEFAULT FALSE,
    answered              BOOLEAN NOT NULL DEFAULT FALSE,
    flagged               BOOLEAN NOT NULL DEFAULT FALSE,
    draft                 BOOLEAN NOT NULL DEFAULT FALSE,
    deleted               BOOLEAN NOT NULL DEFAULT FALSE,
    preserve_target_folder BOOLEAN NOT NULL DEFAULT FALSE,
    source_internal_date  TIMESTAMPTZ,
    error                 TEXT,
    imported_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_external_imported_remote_uid
    ON external_mail_imported_messages(account_id, remote_folder, coalesce(uid_validity, 0), remote_uid);
CREATE INDEX idx_external_imported_raw_ref ON external_mail_imported_messages(raw_ref) WHERE raw_ref IS NOT NULL;
CREATE INDEX idx_external_imported_sha ON external_mail_imported_messages(account_id, remote_folder, sha256) WHERE sha256 IS NOT NULL;
CREATE INDEX idx_external_imported_status ON external_mail_imported_messages(account_id, status, imported_at DESC);

CREATE TABLE external_mail_sync_runs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     UUID NOT NULL REFERENCES external_mail_accounts(id) ON DELETE CASCADE,
    trigger_type   VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',
    status         VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    folders_count  INT NOT NULL DEFAULT 0,
    fetched_count  INT NOT NULL DEFAULT 0,
    queued_count   INT NOT NULL DEFAULT 0,
    skipped_count  INT NOT NULL DEFAULT 0,
    failed_count   INT NOT NULL DEFAULT 0,
    error          TEXT
);
CREATE INDEX idx_external_sync_run_account ON external_mail_sync_runs(account_id, started_at DESC);

CREATE TABLE external_mail_audit_events (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id UUID REFERENCES external_mail_accounts(id) ON DELETE SET NULL,
    actor_id   UUID,
    owner_id   UUID,
    action     VARCHAR(64) NOT NULL,
    details    VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_external_audit_account ON external_mail_audit_events(account_id, created_at DESC);
CREATE INDEX idx_external_audit_owner ON external_mail_audit_events(owner_id, created_at DESC);
