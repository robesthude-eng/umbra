-- v0.19: состояние OTP переезжает из памяти процесса в хранилище.
-- Коды переживают рестарт сервера, лимит «не более 4096 номеров» снимается.

CREATE TABLE IF NOT EXISTS otp_codes (
    phone      TEXT        NOT NULL,
    purpose    TEXT        NOT NULL,
    request_id TEXT        NOT NULL,
    code_hash  TEXT        NOT NULL,
    binding    TEXT        NOT NULL DEFAULT '',
    device     TEXT        NOT NULL DEFAULT '',
    legacy     BOOLEAN     NOT NULL DEFAULT FALSE,
    attempts   INTEGER     NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (phone, purpose)
);

CREATE INDEX IF NOT EXISTS otp_codes_expires_idx ON otp_codes (expires_at);

-- Бюджет отправок и неудачных попыток на номер (окно + кулдаун).
CREATE TABLE IF NOT EXISTS otp_budgets (
    phone        TEXT        PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT to_timestamp(0),
    sends        INTEGER     NOT NULL DEFAULT 0,
    failures     INTEGER     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS otp_budgets_window_idx ON otp_budgets (window_start);
