BEGIN;
-- «Был(а) в сети»: время последнего запроса и взаимное скрытие статуса.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS hide_last_seen BOOLEAN NOT NULL DEFAULT FALSE;
COMMIT;
