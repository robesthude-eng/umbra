package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// PutAccountTransfer сохраняет vault переноса под одноразовым кодом (хранится
// только его SHA-256) и отзывает предыдущие неиспользованные коды пользователя,
// чтобы у аккаунта в любой момент был только один действующий код переноса.
func (p *PostgresStore) PutAccountTransfer(ctx context.Context, userID, codeHash string, vault []byte, expiresAt time.Time) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `DELETE FROM account_transfers WHERE user_id = $1 AND used = FALSE`, userID); err != nil {
		return err
	}
	_, err = tx.Exec(ctx,
		`INSERT INTO account_transfers (code_hash, user_id, vault, expires_at)
		 VALUES ($1, $2, $3, $4)`,
		codeHash, userID, vault, expiresAt)
	if err != nil {
		return mapErr(err)
	}
	return tx.Commit(ctx)
}

// TakeAccountTransfer забирает vault по коду ровно один раз. Одновременные
// попытки claim одного кода разрешаются блокировкой строки: выигрывает один.
func (p *PostgresStore) TakeAccountTransfer(ctx context.Context, codeHash string) (string, []byte, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return "", nil, err
	}
	defer tx.Rollback(ctx)
	var userID string
	var vault []byte
	err = tx.QueryRow(ctx,
		`SELECT user_id, vault FROM account_transfers
		 WHERE code_hash = $1 AND used = FALSE AND expires_at > now()
		 FOR UPDATE`, codeHash).Scan(&userID, &vault)
	if err != nil {
		if err == pgx.ErrNoRows {
			// Просроченные/использованные коды убирает PurgeExpired; здесь не навязываем.
			return "", nil, ErrNotFound
		}
		return "", nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE account_transfers SET used = TRUE WHERE code_hash = $1`, codeHash); err != nil {
		return "", nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return "", nil, err
	}
	return userID, vault, nil
}
