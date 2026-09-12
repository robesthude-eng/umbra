-- 014: область видимости медиа.
--
-- До этой миграции скачать blob мог любой авторизованный пользователь, знающий
-- id: сервер не проверял, состоит ли он в переписке. Теперь клиент при загрузке
-- указывает адресата (recipient_id) или чат (chat_id), а сервер пускает к файлу
-- только владельца, адресата или участников чата.
--
-- Обе колонки nullable: строки, созданные старыми клиентами, остаются без
-- области видимости. Доступ к ним закрыт, если явно не включён
-- MEDIA_LEGACY_OPEN_ACCESS=1 (аватары продолжают работать: они разрешены
-- отдельной проверкой по user_profiles.avatar_media_id).
ALTER TABLE media ADD COLUMN IF NOT EXISTS chat_id      TEXT REFERENCES chats(id) ON DELETE CASCADE;
ALTER TABLE media ADD COLUMN IF NOT EXISTS recipient_id TEXT REFERENCES users(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS media_chat_idx ON media(chat_id) WHERE chat_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS media_recipient_idx ON media(recipient_id) WHERE recipient_id IS NOT NULL;
