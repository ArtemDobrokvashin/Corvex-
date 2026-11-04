-- Версия нужна потребителям: relay/notification могут подтвердить фактически загруженную policy.
-- config содержит несекретные поля и только AES-GCM ciphertext для SMTP/Telegram/MAX secrets.
CREATE TABLE alert_runtime_config (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    config      JSONB NOT NULL,
    revision    BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_by  UUID,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
