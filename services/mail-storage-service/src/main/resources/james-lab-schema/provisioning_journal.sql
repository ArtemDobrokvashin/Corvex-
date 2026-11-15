-- Opt-in development schema; NOT a Flyway migration and not applied on startup.
-- Apply only alongside mailbox_bindings.sql to a dedicated test database.
CREATE TABLE corvex_james_provisioning (
    operation_id UUID PRIMARY KEY,
    owner_id UUID NOT NULL UNIQUE,
    actor_id UUID NOT NULL,
    james_login TEXT NOT NULL UNIQUE CHECK (james_login ~ '^cvx-[0-9a-f]{32}@localhost$'),
    phase TEXT NOT NULL CHECK (phase IN ('reserved','submitting','created','uncertain','bound')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE corvex_james_provisioning_event (
    event_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES corvex_james_provisioning(operation_id),
    actor_id UUID NOT NULL,
    event TEXT NOT NULL CHECK (event IN ('reserved','submitting','created','uncertain','bound')),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
