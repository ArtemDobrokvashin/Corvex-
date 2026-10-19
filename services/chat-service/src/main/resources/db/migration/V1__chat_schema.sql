-- chat_db: комнаты (DM/GROUP/CHANNEL), сообщения, реакции, прочтения, треды.
CREATE TABLE chat_rooms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('DIRECT','GROUP','CHANNEL_PUBLIC','CHANNEL_PRIVATE','SYSTEM')),
    title VARCHAR(256),                       -- NULL для DIRECT (заголовок = собеседник)
    owner_id UUID NOT NULL,
    read_only BOOLEAN NOT NULL DEFAULT FALSE, -- системные каналы объявлений
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_members (
    room_id UUID NOT NULL REFERENCES chat_rooms(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('OWNER','ADMIN','MEMBER','READONLY')),
    muted_until TIMESTAMPTZ,
    last_read_message_id UUID,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);
CREATE INDEX idx_members_user ON chat_members(user_id);

CREATE TABLE chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id UUID NOT NULL REFERENCES chat_rooms(id) ON DELETE CASCADE,
    author_id UUID NOT NULL,
    kind VARCHAR(16) NOT NULL DEFAULT 'TEXT' CHECK (kind IN ('TEXT','FILE','IMAGE','VOICE','VIDEO','SYSTEM')),
    body_markdown TEXT,                       -- Markdown; HTML рендерит и санитизирует фронтенд (DOMPurify)
    thread_root_id UUID REFERENCES chat_messages(id) ON DELETE SET NULL,  -- треды §5.2
    file_ref VARCHAR(256),                    -- -> chat-file-service
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    edited_at TIMESTAMPTZ, deleted_at TIMESTAMPTZ,  -- удаление мягкое: UI покажет «сообщение удалено»
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_msgs_room_time ON chat_messages(room_id, created_at DESC);
CREATE INDEX idx_msgs_thread ON chat_messages(thread_root_id) WHERE thread_root_id IS NOT NULL;

CREATE TABLE message_edit_history (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    old_body TEXT NOT NULL, edited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, edited_at)
);

CREATE TABLE message_reactions (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    user_id UUID NOT NULL, emoji VARCHAR(16) NOT NULL,
    PRIMARY KEY (message_id, user_id, emoji)
);

CREATE TABLE message_mentions (
    message_id UUID NOT NULL REFERENCES chat_messages(id) ON DELETE CASCADE,
    mentioned_user_id UUID NOT NULL,
    PRIMARY KEY (message_id, mentioned_user_id)
);
CREATE INDEX idx_mentions_user ON message_mentions(mentioned_user_id);
