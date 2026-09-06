-- 003_groups.sql — групповые чаты, каналы, участники, контакты.
-- Сервер хранит только ciphertext; групповые ключи (Sender Keys / MLS) живут на клиентах.

CREATE TABLE IF NOT EXISTS chats (
    id         TEXT PRIMARY KEY,
    type       TEXT NOT NULL CHECK (type IN ('group','channel')),
    title      TEXT NOT NULL,
    created_by TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chat_members (
    chat_id   TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role      TEXT NOT NULL DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, user_id)
);
CREATE INDEX IF NOT EXISTS chat_members_user_idx ON chat_members(user_id);

-- Сообщение может быть групповым: recipient_id становится необязательным,
-- адресация — через chat_id.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS chat_id TEXT;
ALTER TABLE messages ALTER COLUMN recipient_id DROP NOT NULL;
CREATE INDEX IF NOT EXISTS messages_chat_idx ON messages(chat_id, created_at);

CREATE TABLE IF NOT EXISTS contacts (
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, contact_id)
);
