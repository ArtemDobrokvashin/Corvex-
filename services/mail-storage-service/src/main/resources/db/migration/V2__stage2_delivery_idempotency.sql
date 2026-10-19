-- Stage 2: идемпотентность доставки и защита системных папок от дублей.
-- raw_ref связывает копию письма в ящике с durable spool-объектом SMTP-приёма;
-- повторная доставка того же AMQP-события тому же владельцу не должна плодить письма.
ALTER TABLE message_metadata ADD COLUMN raw_ref VARCHAR(64);
CREATE UNIQUE INDEX uq_msg_raw_owner
    ON message_metadata(raw_ref, owner_id)
    WHERE raw_ref IS NOT NULL;
CREATE INDEX idx_msg_raw_ref ON message_metadata(raw_ref) WHERE raw_ref IS NOT NULL;

-- UNIQUE(owner_id, parent_id, name) из V1 не защищает корневые папки: NULL parent_id
-- в PostgreSQL считаются различными. Для SPECIAL-USE папок нужен отдельный частичный индекс.
CREATE UNIQUE INDEX uq_folder_owner_special_use
    ON mail_folders(owner_id, special_use)
    WHERE special_use IS NOT NULL;
