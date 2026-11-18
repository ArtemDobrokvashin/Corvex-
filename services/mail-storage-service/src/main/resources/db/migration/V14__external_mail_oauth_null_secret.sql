-- OAuth2-ящик не хранит пароль: доступ выдан согласием провайдера, а токены лежат
-- в external_mail_oauth_tokens. V4 объявила secret_enc NOT NULL, поэтому подключение
-- Gmail/Outlook через OAuth падало на вставке (SQLState 23502) и отвечало 500 —
-- при том что юнит-тест ожидал secretEnc = null и ограничения схемы не видел.
ALTER TABLE external_mail_accounts
    ALTER COLUMN secret_enc DROP NOT NULL;

-- Инвариант для парольных ящиков сохраняется: без секрета такой ящик бесполезен,
-- и «пустой пароль» должен быть ошибкой записи, а не тихим отказом при сборе.
ALTER TABLE external_mail_accounts
    ADD CONSTRAINT external_mail_accounts_secret_by_auth_type
    CHECK (auth_type = 'OAUTH2' OR secret_enc IS NOT NULL);
