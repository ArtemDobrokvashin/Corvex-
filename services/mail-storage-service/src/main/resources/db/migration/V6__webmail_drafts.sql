-- Webmail drafts are separate from IMAP messages until the MIME/IMAP integration is implemented.
-- The payload contains message content and attachments, so mail_db backups contain private data.
CREATE TABLE webmail_drafts (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    payload JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_webmail_drafts_owner_updated ON webmail_drafts (owner_id, updated_at DESC);
