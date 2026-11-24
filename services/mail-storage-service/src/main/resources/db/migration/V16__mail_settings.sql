-- Настройки почты на сервере (P3-1).
--
-- До этого подпись, плотность списка и порядок сортировки жили только в
-- localStorage браузера: на другом устройстве их не было, а в письмах,
-- отправленных из Thunderbird, подписи не было вовсе.
--
-- Хранится JSONB, а не 12 колонок: набор настроек меняется чаще, чем схема, а
-- читает их только сам webmail. Валидация — в MailSettingsService, чтобы
-- мусор из одной вкладки не разъехался по всем.
CREATE TABLE webmail_settings (
    owner_id    UUID PRIMARY KEY,
    payload     JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Подпись для писем, отправленных НЕ из webmail (SMTP submission из
-- Thunderbird/Outlook): подставляется только при явном включении
-- signature_for_submission, иначе письма из чужих клиентов молча менялись бы.
ALTER TABLE webmail_settings
    ADD COLUMN signature_text TEXT,
    ADD COLUMN signature_for_submission BOOLEAN NOT NULL DEFAULT FALSE;
