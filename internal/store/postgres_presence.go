package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// «Был(а) в сети» живёт в users (см. migrations/017_user_presence.sql).

func (p *PostgresStore) TouchPresence(ctx context.Context, userID string, at time.Time) error {
	if at.IsZero() {
		at = time.Now()
	}
	tag, err := p.pool.Exec(ctx,
		`UPDATE users SET last_seen_at = $2 WHERE id = $1`, userID, at.UTC())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) GetPresence(ctx context.Context, userID string) (time.Time, bool, error) {
	var (
		lastSeen *time.Time
		hidden   bool
	)
	err := p.pool.QueryRow(ctx,
		`SELECT last_seen_at, hide_last_seen FROM users WHERE id = $1`, userID).
		Scan(&lastSeen, &hidden)
	if err != nil {
		if err == pgx.ErrNoRows {
			return time.Time{}, false, ErrNotFound
		}
		return time.Time{}, false, err
	}
	if lastSeen == nil {
		return time.Time{}, hidden, nil
	}
	return lastSeen.UTC(), hidden, nil
}

func (p *PostgresStore) SetPresenceHidden(ctx context.Context, userID string, hidden bool) error {
	tag, err := p.pool.Exec(ctx,
		`UPDATE users SET hide_last_seen = $2 WHERE id = $1`, userID, hidden)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
