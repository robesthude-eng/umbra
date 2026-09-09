-- 0.8.0: push-уведомления. Токены устройств Firebase Cloud Messaging.
-- Первичный ключ — сам токен: Firebase меняет его без уведомления, а одно
-- и то же устройство после переустановки приходит с новым токеном.
-- Удаление аккаунта уносит и токены (ON DELETE CASCADE).
BEGIN;
CREATE TABLE IF NOT EXISTS push_devices (
    token      TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform   TEXT NOT NULL DEFAULT 'android'
               CHECK (platform IN ('android')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS push_devices_user_idx ON push_devices(user_id);
COMMIT;
