-- 001_init.sql — начальная схема БД PostgreSQL.
-- ВАЖНО: таблицы хранят только публичные ключи и ciphertext.

CREATE TABLE IF NOT EXISTS users (
    id                 TEXT PRIMARY KEY,
    username           TEXT NOT NULL UNIQUE,
    identity_ed25519   BYTEA NOT NULL,
    identity_x25519    BYTEA NOT NULL,
    signed_prekey      BYTEA NOT NULL,
    signed_prekey_sig  BYTEA NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS one_time_prekeys (
    id       BIGSERIAL PRIMARY KEY,
    user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    prekey   BYTEA NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_otp_user ON one_time_prekeys(user_id);

CREATE TABLE IF NOT EXISTS auth_tokens (
    token_hash TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_user ON auth_tokens(user_id);

CREATE TABLE IF NOT EXISTS messages (
    id           TEXT PRIMARY KEY,
    sender_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ciphertext   BYTEA NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_msg_recipient ON messages(recipient_id, created_at);
