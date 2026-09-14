-- 018_message_reads.sql — «Прочитано» в личной переписке.
-- Храним только курсор чтения: кто, чью переписку и до какого момента прочитал.
-- Отметка на каждое сообщение стоила бы строки на каждое сообщение и смысла не добавляет.
BEGIN;
CREATE TABLE IF NOT EXISTS message_reads (
    reader_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    peer_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    read_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (reader_id, peer_id)
);
CREATE INDEX IF NOT EXISTS idx_message_reads_peer ON message_reads(peer_id);
COMMIT;
