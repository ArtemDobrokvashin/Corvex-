-- Массовый запуск переноса: администратор загружает CSV email, source_host, source_login
-- без паролей. Пароль вводит владелец ящика в мастере — строка здесь только приглашение
-- и подсказка, какой источник подключать.
CREATE TABLE mail_migration_invitations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID NOT NULL,
    email        VARCHAR(320) NOT NULL,
    source_host  VARCHAR(255),
    source_login VARCHAR(320),
    status       VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by   UUID,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner_id, email)
);

CREATE INDEX idx_migration_invitation_status ON mail_migration_invitations(status, created_at DESC);
