-- Stage 3 IMAP CONDSTORE (RFC 7162): per-folder HIGHESTMODSEQ and per-message MODSEQ.
ALTER TABLE mail_folders ADD COLUMN IF NOT EXISTS highest_modseq BIGINT NOT NULL DEFAULT 1;
ALTER TABLE message_metadata ADD COLUMN IF NOT EXISTS modseq BIGINT NOT NULL DEFAULT 1;

-- Backfill existing rows deterministically from UID so old mailboxes get a sane starting point.
UPDATE message_metadata SET modseq = GREATEST(modseq, uid);
UPDATE mail_folders f
SET highest_modseq = GREATEST(
    f.highest_modseq,
    COALESCE((SELECT MAX(m.modseq) FROM message_metadata m WHERE m.folder_id = f.id), 1)
);

CREATE INDEX IF NOT EXISTS idx_msg_folder_modseq ON message_metadata(folder_id, modseq);
