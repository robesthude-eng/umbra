-- v0.19: инвайт-коды. Владелец сервера выдаёт код вместо ручного
-- редактирования AUTH_ALLOWED_PHONES и перезапуска сервера.

CREATE TABLE IF NOT EXISTS invite_codes (
    id         TEXT        PRIMARY KEY,
    owner_id   TEXT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  TEXT        NOT NULL UNIQUE,
    label      TEXT        NOT NULL DEFAULT '',
    max_uses   INTEGER     NOT NULL DEFAULT 1,
    uses       INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS invite_codes_owner_idx ON invite_codes (owner_id, created_at DESC);
CREATE INDEX IF NOT EXISTS invite_codes_expires_idx ON invite_codes (expires_at);

-- Кто и когда воспользовался кодом. Телефон хранится только как хеш.
CREATE TABLE IF NOT EXISTS invite_uses (
    invite_id  TEXT        NOT NULL REFERENCES invite_codes(id) ON DELETE CASCADE,
    phone_hash TEXT        NOT NULL,
    user_id    TEXT,
    used_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (invite_id, phone_hash)
);
