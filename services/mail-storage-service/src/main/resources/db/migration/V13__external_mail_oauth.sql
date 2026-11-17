-- OAuth2 для Gmail и Microsoft 365. Пароль приложения здесь не подходит: Microsoft
-- отключил basic auth в 2022, а Google в рабочих доменах требует OAuth по политике.
--
-- Три таблицы разделены намеренно:
--   * states — короткоживущее состояние входа (PKCE-verifier и адрес возврата);
--   * connections — токены, полученные после согласия, до создания внешнего ящика;
--   * tokens — долговременные токены уже подключённого ящика.
-- Токены хранятся только в зашифрованном виде (CORVEX_EXTERNAL_MAIL_ENC_KEY).

CREATE TABLE external_mail_oauth_states (
    state_hash        VARCHAR(64) PRIMARY KEY,
    owner_id          UUID NOT NULL,
    actor_id          UUID NOT NULL,
    provider          VARCHAR(24) NOT NULL,
    code_verifier_enc TEXT NOT NULL,
    return_path       VARCHAR(200) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,
    used_at           TIMESTAMPTZ
);

CREATE INDEX idx_oauth_state_expires ON external_mail_oauth_states(expires_at);

CREATE TABLE external_mail_oauth_connections (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id          UUID NOT NULL,
    provider          VARCHAR(24) NOT NULL,
    email             VARCHAR(320) NOT NULL,
    access_token_enc  TEXT NOT NULL,
    refresh_token_enc TEXT,
    expires_at        TIMESTAMPTZ NOT NULL,
    scope             VARCHAR(512),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    consumed_at       TIMESTAMPTZ
);

CREATE INDEX idx_oauth_connection_owner ON external_mail_oauth_connections(owner_id, created_at DESC);

CREATE TABLE external_mail_oauth_tokens (
    account_id        UUID PRIMARY KEY REFERENCES external_mail_accounts(id) ON DELETE CASCADE,
    provider          VARCHAR(24) NOT NULL,
    email             VARCHAR(320) NOT NULL,
    access_token_enc  TEXT NOT NULL,
    refresh_token_enc TEXT,
    expires_at        TIMESTAMPTZ NOT NULL,
    scope             VARCHAR(512),
    reauth_required   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
