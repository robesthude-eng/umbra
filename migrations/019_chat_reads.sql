-- 019_chat_reads.sql — прочтения в группах и каналах.
-- Так же, как и в личных чатах, хранится один курсор на участника,
-- а не отметка на каждое сообщение: рост таблицы ограничен числом людей.
BEGIN;
CREATE TABLE IF NOT EXISTS chat_reads (
    chat_id   TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    reader_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (chat_id, reader_id)
);
CREATE INDEX IF NOT EXISTS idx_chat_reads_chat ON chat_reads(chat_id);
COMMIT;
