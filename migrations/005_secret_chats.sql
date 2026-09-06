-- 005_secret_chats.sql — самоуничтожающиеся сообщения (секретные чаты).
-- Сервер хранит момент истечения и не отдаёт просроченные сообщения;
-- фактическое удаление на клиенте, но сервер не возвращает их после expires_at.

ALTER TABLE messages ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS messages_expires_idx ON messages(expires_at);
