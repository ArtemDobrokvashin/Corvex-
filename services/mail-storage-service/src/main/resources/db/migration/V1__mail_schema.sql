-- mail_db: папки, метаданные писем, вложения (CAS), правила, контакты, квоты.
CREATE TABLE mail_folders (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id  UUID NOT NULL,
    parent_id UUID REFERENCES mail_folders(id) ON DELETE CASCADE,
    name      VARCHAR(256) NOT NULL,
    -- INBOX/SENT/DRAFTS/TRASH/SPAM/ARCHIVE для системных; NULL — пользовательская
    special_use VARCHAR(16),
    uid_next  BIGINT NOT NULL DEFAULT 1,       -- следующий IMAP UID
    uid_validity BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, parent_id, name)
);
CREATE INDEX idx_folders_owner ON mail_folders(owner_id);

CREATE TABLE message_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL,
    folder_id UUID NOT NULL REFERENCES mail_folders(id) ON DELETE CASCADE,
    uid BIGINT NOT NULL,
    message_id_header VARCHAR(998),
    sender VARCHAR(320) NOT NULL,
    subject VARCHAR(998) NOT NULL DEFAULT '',
    snippet VARCHAR(512),
    internal_date TIMESTAMPTZ NOT NULL DEFAULT now(),
    size_bytes BIGINT NOT NULL,
    maildir_path VARCHAR(1024) NOT NULL,
    seen BOOLEAN NOT NULL DEFAULT FALSE, answered BOOLEAN NOT NULL DEFAULT FALSE,
    flagged BOOLEAN NOT NULL DEFAULT FALSE, draft BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    spam_score DOUBLE PRECISION,
    av_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (av_status IN ('PENDING','CLEAN','INFECTED','ERROR')),
    UNIQUE (folder_id, uid)
);
CREATE INDEX idx_msg_folder_uid ON message_metadata(folder_id, uid);
CREATE INDEX idx_msg_owner_date ON message_metadata(owner_id, internal_date DESC);
CREATE INDEX idx_msg_unseen ON message_metadata(folder_id) WHERE seen = FALSE;

CREATE TABLE message_recipients (
    message_id UUID NOT NULL REFERENCES message_metadata(id) ON DELETE CASCADE,
    kind VARCHAR(4) NOT NULL CHECK (kind IN ('TO','CC','BCC')),
    address VARCHAR(320) NOT NULL, display_name VARCHAR(256),
    PRIMARY KEY (message_id, kind, address)
);

-- Вложения: sha256 — PK и физический ключ в CAS (/data/attachments/ab/cd/<hash>): дедупликация между ящиками.
CREATE TABLE attachments (
    sha256 VARCHAR(64) PRIMARY KEY,
    size_bytes BIGINT NOT NULL, content_type VARCHAR(256),
    av_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE message_attachments (
    message_id UUID NOT NULL REFERENCES message_metadata(id) ON DELETE CASCADE,
    sha256 VARCHAR(64) NOT NULL REFERENCES attachments(sha256),
    file_name VARCHAR(512) NOT NULL, part_index INT NOT NULL,
    PRIMARY KEY (message_id, part_index)
);

CREATE TABLE mail_rules (  -- пользовательские фильтры сортировки (применяются при доставке, до INBOX)
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL, position INT NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
    name VARCHAR(256) NOT NULL,
    conditions JSONB NOT NULL,   -- [{field:from|subject|to, op:contains|equals|regex, value:...}]
    actions    JSONB NOT NULL,   -- [{type:move, folderId}, {type:markRead}, {type:flag}]
    UNIQUE (owner_id, position)
);

CREATE TABLE contacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL, group_name VARCHAR(128),
    display_name VARCHAR(256) NOT NULL, email VARCHAR(320) NOT NULL,
    UNIQUE (owner_id, email)
);

CREATE TABLE signatures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL, name VARCHAR(128) NOT NULL,
    html TEXT NOT NULL, is_default BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (owner_id, name)
);

CREATE TABLE vacation_autoreplies (
    owner_id UUID PRIMARY KEY, enabled BOOLEAN NOT NULL DEFAULT FALSE,
    subject VARCHAR(256), body TEXT, active_from DATE, active_to DATE
);
