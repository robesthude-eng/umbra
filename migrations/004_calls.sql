-- 004_calls.sql — метаданные звонков (голосовые и видео).
-- Медиа-поток идёт peer-to-peer (WebRTC), сервер хранит только запись о звонке.
-- Сигналинг (offer/answer/ice) ретранслируется через WebSocket и НЕ сохраняется.

CREATE TABLE IF NOT EXISTS calls (
    id         TEXT PRIMARY KEY,
    caller_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    callee_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    video      BOOLEAN NOT NULL DEFAULT false,
    status     TEXT NOT NULL DEFAULT 'ringing'
               CHECK (status IN ('ringing','active','ended','missed','declined')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS calls_callee_idx ON calls(callee_id, status);
CREATE INDEX IF NOT EXISTS calls_caller_idx ON calls(caller_id, status);
