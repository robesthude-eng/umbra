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
