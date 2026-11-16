-- Состояние предложения переноса почты. Без него мастер нельзя показать один раз:
-- флаг mustChangePassword снимается после смены пароля, и любой перезаход снова
-- открывал бы экран «Перенести почту».
CREATE TABLE user_onboarding (
    user_id        UUID PRIMARY KEY,
    migration_state VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    dismissed_at   TIMESTAMPTZ,
    not_needed_at  TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ
);
