package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"umbra/server/internal/model"
)

// PostgresStore — продакшн-хранилище на PostgreSQL (через pgx).
type PostgresStore struct {
	pool *pgxpool.Pool
}

func NewPostgresStore(ctx context.Context, dsn string) (*PostgresStore, error) {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return &PostgresStore{pool: pool}, nil
}

func (p *PostgresStore) CreateUser(ctx context.Context, u *model.User) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	_, err = tx.Exec(ctx,
		`INSERT INTO users (id, username, identity_ed25519, identity_x25519, signed_prekey, signed_prekey_sig)
		 VALUES ($1,$2,$3,$4,$5,$6)`,
		u.ID, u.Username, u.IdentityEd25519, u.IdentityX25519, u.SignedPrekey, u.SignedPrekeySig)
	if err != nil {
		return mapErr(err)
	}
	for _, pk := range u.OneTimePrekeys {
		if _, err = tx.Exec(ctx,
			`INSERT INTO one_time_prekeys (user_id, prekey) VALUES ($1,$2)`, u.ID, pk); err != nil {
			return mapErr(err)
		}
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) GetUserByUsername(ctx context.Context, username string) (*model.User, error) {
	var u model.User
	err := p.pool.QueryRow(ctx,
		`SELECT id, username, identity_ed25519, identity_x25519, signed_prekey, signed_prekey_sig, created_at
		 FROM users WHERE username = $1`, username).
		Scan(&u.ID, &u.Username, &u.IdentityEd25519, &u.IdentityX25519, &u.SignedPrekey, &u.SignedPrekeySig, &u.CreatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	return &u, nil
}

func (p *PostgresStore) GetUserByID(ctx context.Context, id string) (*model.User, error) {
	var u model.User
	err := p.pool.QueryRow(ctx,
		`SELECT id, username, identity_ed25519, identity_x25519, signed_prekey, signed_prekey_sig, created_at
		 FROM users WHERE id = $1`, id).
		Scan(&u.ID, &u.Username, &u.IdentityEd25519, &u.IdentityX25519, &u.SignedPrekey, &u.SignedPrekeySig, &u.CreatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	return &u, nil
}

func (p *PostgresStore) TakeOneTimePrekey(ctx context.Context, userID string) ([]byte, error) {
	var pk []byte
	err := p.pool.QueryRow(ctx,
		`DELETE FROM one_time_prekeys
		 WHERE id = (SELECT id FROM one_time_prekeys WHERE user_id = $1 ORDER BY id LIMIT 1)
		 RETURNING prekey`, userID).Scan(&pk)
	if err != nil {
		return nil, mapErr(err)
	}
	return pk, nil
}

func (p *PostgresStore) PutToken(ctx context.Context, tokenHash, userID string, expires time.Time) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO auth_tokens (token_hash, user_id, expires_at) VALUES ($1,$2,$3)`,
		tokenHash, userID, expires)
	return mapErr(err)
}

func (p *PostgresStore) GetUserIDByTokenHash(ctx context.Context, tokenHash string) (string, error) {
	var userID string
	err := p.pool.QueryRow(ctx,
		`SELECT user_id FROM auth_tokens WHERE token_hash = $1 AND expires_at > now()`, tokenHash).Scan(&userID)
	if err != nil {
		return "", mapErr(err)
	}
	return userID, nil
}

func (p *PostgresStore) DeleteToken(ctx context.Context, tokenHash string) error {
	_, err := p.pool.Exec(ctx, `DELETE FROM auth_tokens WHERE token_hash = $1`, tokenHash)
	return mapErr(err)
}

func (p *PostgresStore) SaveMessage(ctx context.Context, m *model.Message) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO messages (id, sender_id, recipient_id, ciphertext) VALUES ($1,$2,$3,$4)`,
		m.ID, m.SenderID, m.RecipientID, m.Ciphertext)
	return mapErr(err)
}

func (p *PostgresStore) ListMessages(ctx context.Context, userID string, since time.Time) ([]*model.Message, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT id, sender_id, recipient_id, ciphertext, created_at
		 FROM messages WHERE recipient_id = $1 AND created_at > $2 ORDER BY created_at ASC`,
		userID, since)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	out := make([]*model.Message, 0)
	for rows.Next() {
		var m model.Message
		if err := rows.Scan(&m.ID, &m.SenderID, &m.RecipientID, &m.Ciphertext, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, &m)
	}
	return out, rows.Err()
}

func (p *PostgresStore) SaveMedia(ctx context.Context, m *model.Media) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO media (id, owner_id, content_type, size, created_at) VALUES ($1,$2,$3,$4,$5)`,
		m.ID, m.OwnerID, m.ContentType, m.Size, m.CreatedAt)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23503" { // нет пользователя-владельца
		return ErrNotFound
	}
	return mapErr(err)
}

func (p *PostgresStore) GetMedia(ctx context.Context, id string) (*model.Media, error) {
	var m model.Media
	err := p.pool.QueryRow(ctx,
		`SELECT id, owner_id, content_type, size, created_at FROM media WHERE id = $1`, id).
		Scan(&m.ID, &m.OwnerID, &m.ContentType, &m.Size, &m.CreatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	return &m, nil
}

func (p *PostgresStore) Close() error { p.pool.Close(); return nil }

// mapErr приводит ошибки драйвера к каноничным ErrNotFound / ErrConflict.
func mapErr(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" { // unique_violation
		return ErrConflict
	}
	return err
}
