BEGIN;
ALTER TABLE auth_tokens ADD COLUMN IF NOT EXISTS session_id TEXT NOT NULL DEFAULT gen_random_uuid()::text;
ALTER TABLE auth_tokens ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE auth_tokens ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE auth_tokens ADD COLUMN IF NOT EXISTS device_name TEXT NOT NULL DEFAULT 'Устройство до 0.16.18';
CREATE UNIQUE INDEX IF NOT EXISTS auth_session_id_idx ON auth_tokens(session_id);
UPDATE auth_tokens SET expires_at=LEAST(expires_at,created_at+interval '2160 hours');
ALTER TABLE push_devices ADD COLUMN IF NOT EXISTS session_id TEXT REFERENCES auth_tokens(session_id) ON DELETE CASCADE;
-- App re-registers legacy push bindings on next launch.
DELETE FROM push_devices WHERE session_id IS NULL;
CREATE INDEX IF NOT EXISTS push_session_idx ON push_devices(session_id);
COMMIT;
