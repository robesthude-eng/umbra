package store

import (
	"context"
	"errors"
	"fmt"

	"umbra/server/internal/cloudcrypto"
	"umbra/server/internal/model"
)

// EnableCloudStorage must run before serving requests. A new or incomplete key
// file cannot silently replace the keys used by a previously running server.
func (p *PostgresStore) EnableCloudStorage(ctx context.Context, keys *cloudcrypto.Keyring) error {
	if !keys.Has(keys.ActiveID()) {
		return cloudcrypto.ErrInvalid
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `LOCK TABLE storage_keys IN EXCLUSIVE MODE`); err != nil {
		return fmt.Errorf("cloud storage: apply migrations/015_cloud_storage.sql first: %w", err)
	}
	rows, err := tx.Query(ctx, `SELECT id FROM storage_keys`)
	if err != nil {
		return err
	}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		if !keys.Has(id) {
			rows.Close()
			return errors.New("cloud storage: historical key missing; restore the complete key file")
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO storage_keys(id) VALUES ($1) ON CONFLICT DO NOTHING`, keys.ActiveID()); err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	p.storage = keys
	return nil
}

func messageContext(m *model.Message) []byte {
	return cloudcrypto.Context("message", m.ID, m.SenderID, m.RecipientID, m.ChatID)
}

func (p *PostgresStore) sealMessage(m *model.Message) ([]byte, int, error) {
	if p.storage == nil {
		return m.Ciphertext, 0, nil
	} // Legacy fixtures; main always enables encryption.
	data, err := p.storage.Seal(m.Ciphertext, messageContext(m))
	return data, 1, err
}

func (p *PostgresStore) openMessage(m *model.Message, format int) error {
	switch format {
	case 0:
		return nil
	case 1:
		data, err := p.storage.Open(m.Ciphertext, messageContext(m))
		if err != nil {
			return err
		}
		m.Ciphertext = data
		return nil
	default:
		return cloudcrypto.ErrInvalid
	}
}

func (p *PostgresStore) sealReceipt(hash []byte, sender, client string) ([]byte, error) {
	if p.storage == nil {
		return hash, nil
	}
	return p.storage.Seal(hash, cloudcrypto.Context("receipt", sender, client))
}

func (p *PostgresStore) openReceipt(hash []byte, sender, client string) ([]byte, error) {
	if len(hash) == 32 {
		return hash, nil
	} // Legacy SHA-256; encrypted envelopes are longer.
	return p.storage.Open(hash, cloudcrypto.Context("receipt", sender, client))
}

func (p *PostgresStore) BlobReferenced(ctx context.Context, id string) (bool, error) {
	var found bool
	err := p.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM media WHERE COALESCE(blob_id,id)=$1)`, id).Scan(&found)
	return found, err
}

func (m *MemoryStore) BlobReferenced(_ context.Context, id string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, media := range m.media {
		if media.ObjectID() == id {
			return true, nil
		}
	}
	return false, nil
}

type CloudStorageStatus struct {
	LegacyMessages int64 `json:"legacy_messages"`
	LegacyMedia    int64 `json:"legacy_media"`
	LegacyReceipts int64 `json:"legacy_receipts"`
}

func (p *PostgresStore) CloudStorageStatus(ctx context.Context) (CloudStorageStatus, error) {
	var s CloudStorageStatus
	err := p.pool.QueryRow(ctx, `SELECT
		(SELECT count(*) FROM messages WHERE storage_format=0),
		(SELECT count(*) FROM media WHERE storage_format=0),
		(SELECT count(*) FROM message_receipts WHERE octet_length(request_hash)=32)`).Scan(&s.LegacyMessages, &s.LegacyMedia, &s.LegacyReceipts)
	return s, err
}

// MigrateMessageBatch commits small batches, retaining IDs, TTLs and retry receipts.
func (p *PostgresStore) MigrateMessageBatch(ctx context.Context) (int, error) {
	if p.storage == nil {
		return 0, cloudcrypto.ErrInvalid
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)
	rows, err := tx.Query(ctx, `SELECT id,sender_id,COALESCE(recipient_id,''),COALESCE(chat_id,''),ciphertext
		FROM messages WHERE storage_format=0 ORDER BY id LIMIT 50 FOR UPDATE`)
	if err != nil {
		return 0, err
	}
	var messages []model.Message
	for rows.Next() {
		var m model.Message
		if err := rows.Scan(&m.ID, &m.SenderID, &m.RecipientID, &m.ChatID, &m.Ciphertext); err != nil {
			rows.Close()
			return 0, err
		}
		messages = append(messages, m)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	for i := range messages {
		data, _, err := p.sealMessage(&messages[i])
		if err != nil {
			return 0, err
		}
		if _, err := tx.Exec(ctx, `UPDATE messages SET ciphertext=$2,storage_format=1 WHERE id=$1`, messages[i].ID, data); err != nil {
			return 0, err
		}
	}
	rows, err = tx.Query(ctx, `SELECT sender_id,client_id,request_hash FROM message_receipts
		WHERE octet_length(request_hash)=32 ORDER BY sender_id,client_id LIMIT 50 FOR UPDATE`)
	if err != nil {
		return 0, err
	}
	type receipt struct {
		sender, client string
		hash           []byte
	}
	var receipts []receipt
	for rows.Next() {
		var r receipt
		if err := rows.Scan(&r.sender, &r.client, &r.hash); err != nil {
			rows.Close()
			return 0, err
		}
		receipts = append(receipts, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	for _, r := range receipts {
		data, err := p.sealReceipt(r.hash, r.sender, r.client)
		if err != nil {
			return 0, err
		}
		if _, err := tx.Exec(ctx, `UPDATE message_receipts SET request_hash=$3 WHERE sender_id=$1 AND client_id=$2`, r.sender, r.client, data); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, err
	}
	return len(messages) + len(receipts), nil
}

func (p *PostgresStore) LegacyMedia(ctx context.Context) ([]*model.Media, error) {
	rows, err := p.pool.Query(ctx, `SELECT id,size,COALESCE(blob_id,'') FROM media WHERE storage_format=0 ORDER BY id LIMIT 50`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*model.Media
	for rows.Next() {
		var m model.Media
		if err := rows.Scan(&m.ID, &m.Size, &m.BlobID); err != nil {
			return nil, err
		}
		out = append(out, &m)
	}
	return out, rows.Err()
}

// On ANY commit error the caller must keep the new blob: the server may have
// committed even if its response was lost. GC can reclaim true orphans later.
func (p *PostgresStore) CommitEncryptedMedia(ctx context.Context, id, oldBlob, newBlob string) error {
	if oldBlob == newBlob || newBlob == "" {
		return ErrConflict
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	tag, err := tx.Exec(ctx, `UPDATE media SET blob_id=$3,storage_format=1
		WHERE id=$1 AND storage_format=0 AND COALESCE(blob_id,id)=$2`, id, oldBlob, newBlob)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return ErrConflict
	}
	if _, err := tx.Exec(ctx, `INSERT INTO blob_deletions(id) VALUES ($1) ON CONFLICT DO NOTHING`, oldBlob); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
