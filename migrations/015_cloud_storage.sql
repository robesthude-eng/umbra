-- Cloud encryption at rest. Legacy rows retain format 0 until explicitly migrated.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS storage_format SMALLINT NOT NULL DEFAULT 0
    CHECK (storage_format IN (0, 1));
ALTER TABLE media ADD COLUMN IF NOT EXISTS storage_format SMALLINT NOT NULL DEFAULT 0
    CHECK (storage_format IN (0, 1));
-- Public IDs remain stable while migration atomically switches physical objects.
ALTER TABLE media ADD COLUMN IF NOT EXISTS blob_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS media_blob_id_idx ON media ((COALESCE(blob_id, id)));
-- Only public fingerprints, never key material. Startup requires every registered key.
CREATE TABLE IF NOT EXISTS storage_keys (
    id TEXT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
