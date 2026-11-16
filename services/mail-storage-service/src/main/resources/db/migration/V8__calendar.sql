-- Календарь Corvex: серверные события пользователя.
--
-- Время хранится как timestamptz (момент) плюс IANA-зона события. Это важно:
-- Europe/Amsterdam переключается на летнее время, и «09:00 по местному» должно
-- оставаться 09:00 после перехода, поэтому одного UTC-момента недостаточно.
--
-- Событие не удаляется физически: браузер и другие клиенты могут ещё держать
-- его в списке, а напоминание в очереди — ссылаться на строку. deleted_at
-- скрывает событие из API, но сохраняет историю и целостность доставок.

CREATE TABLE calendar_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id            UUID NOT NULL,
    title               VARCHAR(200) NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    location            VARCHAR(200) NOT NULL DEFAULT '',
    starts_at           TIMESTAMPTZ NOT NULL,
    ends_at             TIMESTAMPTZ NOT NULL,
    all_day             BOOLEAN NOT NULL DEFAULT FALSE,
    time_zone           VARCHAR(64) NOT NULL,
    priority            VARCHAR(8) NOT NULL DEFAULT 'medium',
    busy_status         VARCHAR(12) NOT NULL DEFAULT 'busy',
    recurrence          VARCHAR(12) NOT NULL DEFAULT 'none',
    reminder_minutes    INTEGER,
    -- Участники — по одному адресу в строке. Отдельная таблица дала бы join на
    -- каждый список, а список ограничен 50 адресами и всегда читается целиком.
    attendees           TEXT NOT NULL DEFAULT '',
    source_kind         VARCHAR(8) NOT NULL DEFAULT 'NONE',
    source_mail_id      UUID,
    source_mail_subject VARCHAR(512),
    source_mail_sender  VARCHAR(512),
    revision            BIGINT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT calendar_events_time_order CHECK (ends_at > starts_at),
    CONSTRAINT calendar_events_title_not_blank CHECK (length(btrim(title)) > 0),
    CONSTRAINT calendar_events_priority CHECK (priority IN ('high', 'medium', 'low')),
    CONSTRAINT calendar_events_busy CHECK (busy_status IN ('busy', 'tentative', 'free')),
    CONSTRAINT calendar_events_recurrence CHECK (recurrence IN ('none', 'daily', 'weekdays', 'weekly', 'monthly')),
    CONSTRAINT calendar_events_source CHECK (source_kind IN ('NONE', 'MAIL', 'CHAT')),
    CONSTRAINT calendar_events_reminder CHECK
        (reminder_minutes IS NULL OR (reminder_minutes >= 0 AND reminder_minutes <= 10080)),
    -- Источник события из письма должен быть идентифицируем, иначе «открыть письмо»
    -- из календаря приведёт в пустоту.
    CONSTRAINT calendar_events_mail_source CHECK
        (source_kind <> 'MAIL' OR source_mail_id IS NOT NULL)
);

-- Основной запрос — события владельца в диапазоне дат; повторяющиеся события
-- читаются отдельно и не отсекаются по starts_at.
CREATE INDEX calendar_events_owner_start_idx ON calendar_events (owner_id, starts_at)
    WHERE deleted_at IS NULL;
CREATE INDEX calendar_events_owner_recurring_idx ON calendar_events (owner_id)
    WHERE deleted_at IS NULL AND recurrence <> 'none';

-- Доставка напоминаний. Строка создаётся заранее (PENDING), чтобы два экземпляра
-- сервиса не отправили одно и то же напоминание дважды, и чтобы перезапуск не
-- потерял ещё не отправленное. Гарантия — at-least-once: падение процесса между
-- отправкой письма и отметкой SENT может дать повтор, но не потерю.
CREATE TABLE calendar_reminder_deliveries (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL REFERENCES calendar_events(id) ON DELETE CASCADE,
    owner_id        UUID NOT NULL,
    occurrence_at   TIMESTAMPTZ NOT NULL,
    due_at          TIMESTAMPTZ NOT NULL,
    status          VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    claimed_at      TIMESTAMPTZ,
    sent_at         TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      VARCHAR(512),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT calendar_reminder_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')),
    CONSTRAINT calendar_reminder_occurrence_unique UNIQUE (event_id, occurrence_at)
);

CREATE INDEX calendar_reminder_due_idx ON calendar_reminder_deliveries (status, next_attempt_at);
