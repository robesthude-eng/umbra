package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"umbra/server/internal/model"
)

func (p *PostgresStore) TakePrekeyBundle(ctx context.Context, username string) (*model.User, []byte, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback(ctx)
	var u model.User
	err = tx.QueryRow(ctx, `SELECT id,username,identity_ed25519,identity_x25519,signed_prekey,signed_prekey_sig,created_at,key_version,registration_id,signed_prekey_id
	 FROM users WHERE username=$1 FOR UPDATE`, username).
		Scan(&u.ID, &u.Username, &u.IdentityEd25519, &u.IdentityX25519, &u.SignedPrekey, &u.SignedPrekeySig, &u.CreatedAt, &u.KeyVersion, &u.RegistrationID, &u.SignedPrekeyID)
	if err != nil {
		return nil, nil, mapErr(err)
	}
	var pk []byte
	err = tx.QueryRow(ctx, `DELETE FROM one_time_prekeys WHERE id=(SELECT id FROM one_time_prekeys WHERE user_id=$1 ORDER BY id LIMIT 1) RETURNING prekey`, u.ID).Scan(&pk)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	return &u, pk, nil
}

func (p *PostgresStore) OneTimePrekeyCount(ctx context.Context, userID string) (int, error) {
	var count int
	err := p.pool.QueryRow(ctx, "SELECT count(*) FROM one_time_prekeys WHERE user_id=$1", userID).Scan(&count)
	return count, err
}

func (p *PostgresStore) UpdateKeys(ctx context.Context, userID string, keys *model.User) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var identity []byte
	var bundleID string
	if err := tx.QueryRow(ctx, `SELECT identity_x25519,key_bundle_id FROM users WHERE id=$1 FOR UPDATE`, userID).Scan(&identity, &bundleID); err != nil {
		return mapErr(err)
	}
	if !sameCurveIdentity(identity, keys.IdentityX25519) {
		return ErrConflict
	}
	if bundleID == keys.KeyBundleID && bundleID != "" {
		return tx.Commit(ctx)
	}
	_, err = tx.Exec(ctx, `UPDATE users SET signed_prekey=$2,signed_prekey_sig=$3,key_version=$4,registration_id=$5,signed_prekey_id=$6,key_bundle_id=$7,identity_x25519=$8 WHERE id=$1`,
		userID, keys.SignedPrekey, keys.SignedPrekeySig, keys.KeyVersion, keys.RegistrationID, keys.SignedPrekeyID, keys.KeyBundleID, keys.IdentityX25519)
	if err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, `DELETE FROM one_time_prekeys WHERE user_id=$1`, userID); err != nil {
		return err
	}
	for _, pk := range keys.OneTimePrekeys {
		if _, err = tx.Exec(ctx, `INSERT INTO one_time_prekeys(user_id,prekey) VALUES ($1,$2)`, userID, pk); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) SaveMediaWithQuota(ctx context.Context, media *model.Media, limit int64) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	var userID string
	if err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, media.OwnerID).Scan(&userID); err != nil {
		return mapErr(err)
	}
	if media.Size < 0 {
		return ErrQuota
	}
	if limit > 0 {
		var used int64
		if err := tx.QueryRow(ctx, `SELECT COALESCE(SUM(size),0) FROM media WHERE owner_id=$1`, userID).Scan(&used); err != nil {
			return err
		}
		if used > limit || media.Size > limit-used {
			return ErrQuota
		}
	}
	_, err = tx.Exec(ctx, `INSERT INTO media(id,owner_id,content_type,size,created_at) VALUES ($1,$2,$3,$4,$5)`,
		media.ID, media.OwnerID, media.ContentType, media.Size, media.CreatedAt)
	if err != nil {
		return mapErr(err)
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) LockBlobs(ctx context.Context, exclusive bool) (func(), error) {
	// Отдельное соединение: upload не должен занимать все pool connections,
	// ожидая второе соединение для записи своих метаданных.
	conn, err := pgx.Connect(ctx, p.pool.Config().ConnString())
	if err != nil {
		return nil, err
	}
	lock, unlock := "pg_advisory_lock_shared", "pg_advisory_unlock_shared"
	if exclusive {
		lock, unlock = "pg_advisory_lock", "pg_advisory_unlock"
	}
	// Постоянный namespace блокировки Umbra, одинаковый для server и gc.
	if _, err := conn.Exec(ctx, "SELECT "+lock+"(734219580)"); err != nil {
		_ = conn.Close(context.Background())
		return nil, err
	}
	return func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, _ = conn.Exec(cleanup, "SELECT "+unlock+"(734219580)")
		_ = conn.Close(cleanup)
	}, nil
}

func (p *PostgresStore) PendingBlobDeletes(ctx context.Context) ([]string, error) {
	rows, err := p.pool.Query(ctx, `SELECT id FROM blob_deletions ORDER BY created_at LIMIT 500`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := make([]string, 0)
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (p *PostgresStore) CompleteBlobDelete(ctx context.Context, id string) error {
	_, err := p.pool.Exec(ctx, `DELETE FROM blob_deletions WHERE id=$1`, id)
	return err
}

func (p *PostgresStore) PurgeExpired(ctx context.Context, now time.Time) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err = tx.Exec(ctx, `DELETE FROM messages WHERE expires_at <= $1`, now); err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, `DELETE FROM auth_tokens WHERE expires_at <= $1`, now); err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, `DELETE FROM message_receipts WHERE created_at < $1`, now.Add(-30*24*time.Hour)); err != nil {
		return err
	}
	if _, err = tx.Exec(ctx, `DELETE FROM account_transfers WHERE expires_at <= $1 OR used = TRUE`, now); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) ListMessagesPage(ctx context.Context, userID string, since time.Time, afterID string, limit int) ([]*model.Message, error) {
	rows, err := p.pool.Query(ctx, `SELECT id,sender_id,recipient_id,chat_id,ciphertext,created_at,expires_at
	 FROM messages WHERE (created_at > $2 OR ($3 <> '' AND created_at=$2 AND id > $3))
	 AND (expires_at IS NULL OR expires_at > now())
	 AND (recipient_id=$1 OR sender_id=$1 OR chat_id IN (SELECT chat_id FROM chat_members WHERE user_id=$1))
	 ORDER BY created_at,id LIMIT $4`, userID, since, afterID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*model.Message, 0)
	for rows.Next() {
		var m model.Message
		var recipient, chat *string
		if err := rows.Scan(&m.ID, &m.SenderID, &recipient, &chat, &m.Ciphertext, &m.CreatedAt, &m.ExpiresAt); err != nil {
			return nil, err
		}
		if recipient != nil {
			m.RecipientID = *recipient
		}
		if chat != nil {
			m.ChatID = *chat
		}
		out = append(out, &m)
	}
	return out, rows.Err()
}
