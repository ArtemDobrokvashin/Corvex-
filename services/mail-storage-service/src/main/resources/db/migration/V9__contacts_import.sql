-- Адресная книга: поля для импорта из vCard/CSV и ручного ввода.
-- Таблица contacts существует с V1 (id, owner_id, group_name, display_name, email),
-- поэтому расширение аддитивное и безопасное для уже существующих строк.
ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS phone       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS organization VARCHAR(256),
    ADD COLUMN IF NOT EXISTS job_title   VARCHAR(256),
    ADD COLUMN IF NOT EXISTS note        TEXT,
    ADD COLUMN IF NOT EXISTS source      VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at  TIMESTAMPTZ NOT NULL DEFAULT now();

-- Поиск идёт по владельцу и имени; список всегда отсортирован по имени.
CREATE INDEX IF NOT EXISTS ix_contacts_owner_name ON contacts (owner_id, display_name);

-- Дедупликация импорта ищет по адресу без учёта регистра, а существующее
-- UNIQUE (owner_id, email) регистр учитывает. Индекс не уникальный: старые строки
-- могли быть заведены в разном регистре, и падать на миграции из-за них нельзя.
CREATE INDEX IF NOT EXISTS ix_contacts_owner_email_lower ON contacts (owner_id, lower(email));
