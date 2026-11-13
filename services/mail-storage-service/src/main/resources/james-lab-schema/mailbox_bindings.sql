-- LAB ONLY, not a Flyway migration. Empty, disabled-by-default bindings only.
-- This script does not provision
-- James accounts, enable a route, or switch ownership of any mailbox.
-- Writes must be performed by a separately authorized provisioning process.
CREATE TABLE corvex_james_mailbox_binding (
    owner_id UUID PRIMARY KEY,
    james_login TEXT NOT NULL CHECK (length(trim(james_login)) BETWEEN 1 AND 320),
    account_id TEXT NOT NULL UNIQUE CHECK (length(trim(account_id)) BETWEEN 1 AND 1024),
    enabled BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX corvex_james_login_unique ON corvex_james_mailbox_binding (lower(james_login));
