-- Задания переноса почты из внешнего ящика. Отдельная таблица, а не поле в
-- external_mail_accounts: у одного ящика бывает несколько попыток (полный проход,
-- delta, повтор после ошибки), и каждая должна иметь собственный отчёт.
CREATE TABLE mail_migration_jobs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID NOT NULL,
    account_id         UUID NOT NULL REFERENCES external_mail_accounts(id) ON DELETE CASCADE,
    created_by         UUID,
    status             VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    mode               VARCHAR(16) NOT NULL DEFAULT 'FULL',
    include_all_mail   BOOLEAN NOT NULL DEFAULT FALSE,
    skip_folders       TEXT,
    estimated_messages BIGINT NOT NULL DEFAULT 0,
    estimated_bytes    BIGINT NOT NULL DEFAULT 0,
    run_id             UUID,
    error              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- «Последнее задание по ящику» и проверка «уже идёт» идут по этим индексам.
CREATE INDEX idx_migration_job_owner ON mail_migration_jobs(owner_id, created_at DESC);
CREATE INDEX idx_migration_job_account ON mail_migration_jobs(account_id, created_at DESC);

CREATE TABLE mail_migration_folders (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id             UUID NOT NULL REFERENCES mail_migration_jobs(id) ON DELETE CASCADE,
    remote_folder      VARCHAR(1024) NOT NULL,
    special_use        VARCHAR(24),
    state              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    estimated_messages INT NOT NULL DEFAULT 0,
    estimated_bytes    BIGINT NOT NULL DEFAULT 0,
    migrated_messages  INT NOT NULL DEFAULT 0,
    skipped_messages   INT NOT NULL DEFAULT 0,
    error              TEXT,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ
);

CREATE INDEX idx_migration_folder_job ON mail_migration_folders(job_id, remote_folder);
