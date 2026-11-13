-- Experimental opt-in schema. NOT a Flyway migration; no live delivery uses it.
-- raw_ref must identify one immutable spool object throughout its lifetime.
CREATE TABLE corvex_james_delivery_intent (
    operation_id UUID PRIMARY KEY,
    raw_ref TEXT NOT NULL CHECK (length(raw_ref) BETWEEN 1 AND 1024),
    owner_id UUID NOT NULL,
    james_account_id TEXT NOT NULL CHECK (length(james_account_id) BETWEEN 1 AND 1024),
    target_folder_id TEXT NOT NULL CHECK (length(target_folder_id) BETWEEN 1 AND 1024),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    phase TEXT NOT NULL CHECK (phase IN ('reserved','submitting','confirmed','uncertain')),
    remote_id TEXT CHECK (remote_id IS NULL OR length(remote_id) BETWEEN 1 AND 1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (raw_ref, owner_id),
    CHECK ((phase = 'confirmed') = (remote_id IS NOT NULL))
);
