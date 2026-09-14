package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// «Прочитано» живёт в message_reads (см. migrations/018_message_reads.sql).

func (p *PostgresStore) SetReadCursor(ctx context.Context, readerID, peerID string, at time.Time) error {
	if at.IsZero() {
		at = time.Now()
	}
	// GREATEST: курсор не откатывается назад, даже если события пришли не по порядку.
	_, err := p.pool.Exec(ctx,
		`INSERT INTO message_reads (reader_id, peer_id, read_at) VALUES ($1, $2, $3)
		 ON CONFLICT (reader_id, peer_id)
		 DO UPDATE SET read_at = GREATEST(message_reads.read_at, EXCLUDED.read_at)`,
		readerID, peerID, at.UTC())
	return err
}

func (p *PostgresStore) GetReadCursor(ctx context.Context, readerID, peerID string) (time.Time, error) {
	var at time.Time
	err := p.pool.QueryRow(ctx,
		`SELECT read_at FROM message_reads WHERE reader_id = $1 AND peer_id = $2`,
		readerID, peerID).Scan(&at)
	if err != nil {
		if err == pgx.ErrNoRows {
			return time.Time{}, nil
		}
		return time.Time{}, err
	}
	return at.UTC(), nil
}

// Прочтения в группах живут в chat_reads (см. migrations/019_chat_reads.sql).

func (p *PostgresStore) SetChatRead(ctx context.Context, chatID, readerID string, at time.Time) error {
	if at.IsZero() {
		at = time.Now()
	}
	_, err := p.pool.Exec(ctx,
		`INSERT INTO chat_reads (chat_id, reader_id, read_at) VALUES ($1, $2, $3)
		 ON CONFLICT (chat_id, reader_id)
		 DO UPDATE SET read_at = GREATEST(chat_reads.read_at, EXCLUDED.read_at)`,
		chatID, readerID, at.UTC())
	return err
}

func (p *PostgresStore) ListChatReads(ctx context.Context, chatID string) (map[string]time.Time, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT reader_id, read_at FROM chat_reads WHERE chat_id = $1`, chatID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]time.Time)
	for rows.Next() {
		var id string
		var at time.Time
		if err := rows.Scan(&id, &at); err != nil {
			return nil, err
		}
		out[id] = at.UTC()
	}
	return out, rows.Err()
}
