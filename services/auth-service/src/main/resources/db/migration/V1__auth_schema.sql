-- auth_db: учётные записи (локальные + зеркало AD), refresh-сессии, TOTP, попытки входа.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64)  NOT NULL UNIQUE,
    email         VARCHAR(320) NOT NULL UNIQUE,
    display_name  VARCHAR(256) NOT NULL,
    -- NULL для AD-пользователей: у них пароля локально нет и быть не должно
    password_hash VARCHAR(256),
    source        VARCHAR(16)  NOT NULL CHECK (source IN ('LOCAL','AD_LDAP')),
    ad_object_guid VARCHAR(64),
    department    VARCHAR(256),
    manager_id    UUID REFERENCES users(id),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BLOCKED','DISABLED')),
    totp_secret_enc TEXT,               -- AES-GCM, ключ CORVEX_TOTP_ENC_KEY
    totp_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    quota_bytes   BIGINT  NOT NULL DEFAULT 5368709120,  -- 5 ГиБ по умолчанию
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source='AD_LDAP' OR password_hash IS NOT NULL)
);
CREATE INDEX idx_users_department ON users(department);

CREATE TABLE refresh_sessions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(128) NOT NULL UNIQUE,       -- SHA-256 от refresh-токена, сам токен не хранится
    user_agent  TEXT, ip_address INET,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE login_attempts (
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(64) NOT NULL,
    ip_address INET, success BOOLEAN NOT NULL, reason VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_attempts_user_time ON login_attempts(username, created_at DESC);
