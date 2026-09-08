-- 008_account_transfer.sql — «Вход на новом телефоне» (v0.4).
-- Одноразовые коды переноса аккаунта между устройствами.
--
-- Модель: приложение на старом телефоне шифрует «цифровую личность» аккаунта
-- (identity-ключи, Signal-сессии, историю переписок) ключом, производным от
-- одноразового кода, и кладёт получившийся непрозрачный blob сюда. Новый телефон
-- предъявляет код (10 минут, однократное использование), получает blob и
-- расшифровывает его локально. Сервер видит только шифрованные байты и хэш кода.
--
-- ВАЖНО: приватные ключи и открытый текст сообщений на сервер НЕ передаются.

CREATE TABLE IF NOT EXISTS account_transfers (
    code_hash  TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vault      BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS account_transfers_user_idx ON account_transfers(user_id);
CREATE INDEX IF NOT EXISTS account_transfers_expiry_idx ON account_transfers(expires_at);
