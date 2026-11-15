-- EXAMPLE ONLY. Do not execute on a production database or use as a Flyway migration.
-- A separate, privileged provisioning workflow must verify a James account before insertion.
CREATE TABLE corvex_james_mailbox_binding (
    owner_id UUID PRIMARY KEY,
    james_login TEXT NOT NULL CHECK (length(trim(james_login)) > 0),
    account_id TEXT NOT NULL UNIQUE CHECK (length(trim(account_id)) > 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX corvex_james_login_unique ON corvex_james_mailbox_binding (lower(james_login));
