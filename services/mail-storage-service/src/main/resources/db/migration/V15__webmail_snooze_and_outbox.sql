-- Snooze (P2-6) и отложенная отправка.
--
-- Snooze не копирует письмо и не удаляет его: строка лишь говорит «до этого
-- момента письмо в списке папки не показывать». Так отмена не требует
-- восстановления UID и не ломает IMAP-нумерацию: пока snooze активен, само
-- письмо остаётся на месте, а список папки его скрывает.
CREATE TABLE webmail_snoozed_messages (
    message_id  UUID PRIMARY KEY REFERENCES message_metadata(id) ON DELETE CASCADE,
    owner_id    UUID NOT NULL,
    -- Папка, в которой письмо было в момент snooze: если за время сна его
    -- переместили, возвращать в старое место не нужно.
    folder_id   UUID NOT NULL REFERENCES mail_folders(id) ON DELETE CASCADE,
    wake_at     TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_snoozed_wake ON webmail_snoozed_messages (wake_at);
CREATE INDEX ix_snoozed_owner ON webmail_snoozed_messages (owner_id);

-- Отложенная отправка: письмо уже собрано в MIME и лежит в спуле, но в SMTP
-- и в «Отправленные» попадает только по наступлении send_at. Так «Отменить
-- отправку» — это DELETE строки, а не попытка догнать ушедшее письмо.
CREATE TABLE webmail_outbox (
    id              UUID PRIMARY KEY,
    owner_id        UUID NOT NULL,
    raw_ref         VARCHAR(64) NOT NULL,
    -- Получатели и права зафиксированы в момент планирования: через час у
    -- пользователя может не быть сессии, но права уже проверены.
    local_recipients    TEXT,
    external_recipients TEXT,
    from_address    VARCHAR(320) NOT NULL,
    message_id_header VARCHAR(998) NOT NULL,
    external_account_id UUID REFERENCES external_mail_accounts(id) ON DELETE SET NULL,
    subject         VARCHAR(998) NOT NULL DEFAULT '',
    save_to_sent    BOOLEAN NOT NULL DEFAULT TRUE,
    send_at         TIMESTAMPTZ NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED'
                    CHECK (status IN ('SCHEDULED','SENDING','SENT','FAILED','CANCELLED')),
    attempts        INT NOT NULL DEFAULT 0,
    last_error      VARCHAR(1024),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_outbox_due ON webmail_outbox (status, send_at);
CREATE INDEX ix_outbox_owner ON webmail_outbox (owner_id, send_at);
