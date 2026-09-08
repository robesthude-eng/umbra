package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// Привязка номера к Telegram-чату (для доставки OTP-кодов, v0.4).

func (p *PostgresStore) BindTelegram(ctx context.Context, phone string, tgChatID int64) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO tg_bindings (phone, tg_chat_id) VALUES ($1, $2)
		 ON CONFLICT (phone) DO UPDATE SET tg_chat_id = $2, updated_at = now()`,
		phone, tgChatID)
	return err
}

func (p *PostgresStore) TelegramChatForPhone(ctx context.Context, phone string) (int64, error) {
	var chatID int64
	err := p.pool.QueryRow(ctx,
		`SELECT tg_chat_id FROM tg_bindings WHERE phone = $1`, phone).Scan(&chatID)
	if err != nil {
		if err == pgx.ErrNoRows {
			return 0, ErrNotFound
		}
		return 0, err
	}
	return chatID, nil
}

// Профиль аккаунта (v0.4, облачная модель).

func (p *PostgresStore) UpdateAccountProfile(ctx context.Context, userID, username, firstName, lastName string) error {
	tag, err := p.pool.Exec(ctx,
		`UPDATE users SET username = $2, display_name = $3, last_name = $4 WHERE id = $1`,
		userID, username, firstName, lastName)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			return ErrConflict
		}
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) SetAvatar(ctx context.Context, userID, mediaID string) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO user_profiles (user_id, avatar_media_id) VALUES ($1, $2)
		 ON CONFLICT (user_id) DO UPDATE SET avatar_media_id = $2, updated_at = now()`,
		userID, mediaID)
	return err
}

func (p *PostgresStore) GetAvatar(ctx context.Context, userID string) (string, error) {
	var mediaID string
	err := p.pool.QueryRow(ctx,
		`SELECT avatar_media_id FROM user_profiles WHERE user_id = $1`, userID).Scan(&mediaID)
	if err != nil {
		if err == pgx.ErrNoRows {
			return "", ErrNotFound
		}
		return "", err
	}
	return mediaID, nil
}
