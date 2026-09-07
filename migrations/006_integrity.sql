-- NOT VALID сохраняет исторические сироты для отдельного разбора, но защищает
-- новые записи и обеспечивает каскадное удаление сообщений существующих чатов.
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'messages_chat_fk' AND conrelid = 'messages'::regclass) THEN
        ALTER TABLE messages ADD CONSTRAINT messages_chat_fk
            FOREIGN KEY (chat_id) REFERENCES chats(id) ON DELETE CASCADE NOT VALID;
    END IF;
END $$;

-- Файлы удаляются через надёжную очередь, даже если S3 недоступен при burn.
CREATE TABLE IF NOT EXISTS blob_deletions (
    id TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS key_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN IF NOT EXISTS registration_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN IF NOT EXISTS signed_prekey_id INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN IF NOT EXISTS key_bundle_id TEXT NOT NULL DEFAULT '';

-- Receipt не содержит сообщения: только хэш запроса и результат отправки.
-- Нужен для безопасного повтора после потери HTTP-ответа, включая истёкшие сообщения.
CREATE TABLE IF NOT EXISTS message_receipts (
    sender_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    request_hash BYTEA NOT NULL,
    message_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (sender_id, client_id)
);
CREATE INDEX IF NOT EXISTS message_receipts_created_idx ON message_receipts(created_at);
CREATE INDEX IF NOT EXISTS media_owner_idx ON media(owner_id);
CREATE INDEX IF NOT EXISTS messages_expiry_idx ON messages(expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS tokens_expiry_idx ON auth_tokens(expires_at);
