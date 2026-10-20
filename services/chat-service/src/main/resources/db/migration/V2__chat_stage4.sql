-- Stage 4: persistent chat.
-- client_message_id делает повторную отправку после reconnect идемпотентной:
-- тот же клиентский ID от того же автора в той же комнате не создаёт дубль.
ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS client_message_id VARCHAR(128);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_messages_client_id
    ON chat_messages(room_id, author_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

-- Список комнат сортируется по последней активности без агрегации по всей истории.
ALTER TABLE chat_rooms
    ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE chat_rooms r
SET last_message_at = COALESCE((
        SELECT max(m.created_at) FROM chat_messages m WHERE m.room_id = r.id
    ), r.created_at);

CREATE INDEX IF NOT EXISTS idx_chat_rooms_last_message
    ON chat_rooms(last_message_at DESC);

-- Курсор истории использует пару (created_at, id), поэтому индекс должен закрывать оба ключа.
CREATE INDEX IF NOT EXISTS idx_chat_messages_room_cursor
    ON chat_messages(room_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_chat_messages_author_time
    ON chat_messages(author_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_chat_members_room_user
    ON chat_members(room_id, user_id);
