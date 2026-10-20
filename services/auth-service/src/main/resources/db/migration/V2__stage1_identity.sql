-- Этап 1 (Identity): обязательная смена временного пароля, пароли приложений,
-- флаг 2FA в refresh-сессии; ip_address INET -> VARCHAR (проще и надёжнее маппинг из JPA).
ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE login_attempts
    ALTER COLUMN ip_address TYPE VARCHAR(64) USING (CASE WHEN ip_address IS NULL THEN NULL ELSE host(ip_address) END);
ALTER TABLE refresh_sessions
    ALTER COLUMN ip_address TYPE VARCHAR(64) USING (CASE WHEN ip_address IS NULL THEN NULL ELSE host(ip_address) END);
ALTER TABLE refresh_sessions ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_refresh_sessions_user_active ON refresh_sessions(user_id) WHERE revoked_at IS NULL;

-- Пароли приложений для внешних почтовых клиентов (IMAP/SMTP, этап 3) и verify-password.
CREATE TABLE app_passwords (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label         VARCHAR(128) NOT NULL,
    password_hash VARCHAR(256) NOT NULL,
    last_used_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_app_passwords_user ON app_passwords(user_id);
