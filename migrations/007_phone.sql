-- 007_phone.sql — регистрация по номеру телефона и приватный поиск контактов.
-- phone хранится в E.164 ("+79991234567"); phone_hash = SHA-256("umbra-phone:" || phone)
-- в hex — по нему работает POST /v1/contacts/discover, не раскрывая телефонную
-- книгу клиента. У legacy-аккаунтов (только username) поля остаются NULL.

ALTER TABLE users ADD COLUMN IF NOT EXISTS phone TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_hash TEXT;
ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name TEXT;

-- Номер уникален среди зарегистрированных; NULL (legacy) не конфликтуют.
CREATE UNIQUE INDEX IF NOT EXISTS users_phone_unique ON users(phone) WHERE phone IS NOT NULL;
-- Поиск контактов идёт по хэшам пачками (до 1000 за запрос).
CREATE INDEX IF NOT EXISTS users_phone_hash_idx ON users(phone_hash) WHERE phone_hash IS NOT NULL;
