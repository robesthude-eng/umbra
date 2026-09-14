package store

import (
	"context"
	"fmt"
	"sort"
	"time"
	"umbra/server/internal/model"
)

const MaxSessionLifetime = 90 * 24 * time.Hour

func capSessionExpiry(created, expires time.Time) time.Time {
	if limit := created.Add(MaxSessionLifetime); expires.After(limit) {
		return limit
	}
	return expires
}
func (p *PostgresStore) CreateSession(ctx context.Context, s *model.AuthSession) error {
	s.ExpiresAt = capSessionExpiry(s.CreatedAt, s.ExpiresAt)
	_, err := p.pool.Exec(ctx, `INSERT INTO auth_tokens(token_hash,user_id,session_id,device_name,created_at,last_seen_at,expires_at) VALUES($1,$2,$3,$4,$5,$6,$7)`, s.TokenHash, s.UserID, s.ID, s.DeviceName, s.CreatedAt, s.LastSeenAt, s.ExpiresAt)
	return mapErr(err)
}
func (p *PostgresStore) ListSessions(ctx context.Context, userID string) ([]model.AuthSession, error) {
	rows, err := p.pool.Query(ctx, `SELECT session_id,token_hash,device_name,created_at,last_seen_at,expires_at FROM auth_tokens WHERE user_id=$1 AND expires_at>now() AND created_at>now()-interval '2160 hours' ORDER BY last_seen_at DESC,session_id`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]model.AuthSession, 0)
	for rows.Next() {
		var s model.AuthSession
		s.UserID = userID
		if err := rows.Scan(&s.ID, &s.TokenHash, &s.DeviceName, &s.CreatedAt, &s.LastSeenAt, &s.ExpiresAt); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}
func (p *PostgresStore) RevokeSession(ctx context.Context, userID, sessionID string) error {
	tag, err := p.pool.Exec(ctx, `DELETE FROM auth_tokens WHERE user_id=$1 AND session_id=$2`, userID, sessionID)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
func (p *PostgresStore) RevokeOtherSessions(ctx context.Context, userID, currentHash string) error {
	_, err := p.pool.Exec(ctx, `DELETE FROM auth_tokens WHERE user_id=$1 AND token_hash<>$2`, userID, currentHash)
	return mapErr(err)
}
func (p *PostgresStore) SavePushDeviceForSession(ctx context.Context, userID, token, platform, hash string) error {
	// FK cascade also covers revocation racing with push registration.
	tag, err := p.pool.Exec(ctx, `INSERT INTO push_devices(token,user_id,platform,updated_at,session_id)
 SELECT $1,$2,$3,now(),session_id FROM auth_tokens WHERE token_hash=$4 AND user_id=$2 AND expires_at>now() AND created_at>now()-interval '2160 hours'
 ON CONFLICT(token) DO UPDATE SET user_id=EXCLUDED.user_id,platform=EXCLUDED.platform,updated_at=EXCLUDED.updated_at,session_id=EXCLUDED.session_id`, token, userID, platform, hash)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
func (m *MemoryStore) CreateSession(_ context.Context, s *model.AuthSession) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[s.UserID]; !ok {
		return ErrNotFound
	}
	if _, ok := m.tokens[s.TokenHash]; ok {
		return ErrConflict
	}
	s.ExpiresAt = capSessionExpiry(s.CreatedAt, s.ExpiresAt)
	m.tokens[s.TokenHash] = tokenEntry{userID: s.UserID, expires: s.ExpiresAt, session: *s}
	return nil
}
func (m *MemoryStore) ListSessions(_ context.Context, userID string) ([]model.AuthSession, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]model.AuthSession, 0)
	now := time.Now()
	for _, t := range m.tokens {
		if t.userID == userID && t.expires.After(now) && t.session.CreatedAt.Add(MaxSessionLifetime).After(now) {
			out = append(out, t.session)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].LastSeenAt.Equal(out[j].LastSeenAt) {
			return out[i].ID < out[j].ID
		}
		return out[i].LastSeenAt.After(out[j].LastSeenAt)
	})
	return out, nil
}
func (m *MemoryStore) deleteSessionLocked(hash string) {
	s, ok := m.tokens[hash]
	if !ok {
		return
	}
	delete(m.tokens, hash)
	for token, d := range m.pushDevices {
		if d.SessionID == s.session.ID {
			delete(m.pushDevices, token)
		}
	}
}
func (m *MemoryStore) RevokeSession(_ context.Context, userID, sessionID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for hash, t := range m.tokens {
		if t.userID == userID && t.session.ID == sessionID {
			m.deleteSessionLocked(hash)
			return nil
		}
	}
	return ErrNotFound
}
func (m *MemoryStore) RevokeOtherSessions(_ context.Context, userID, currentHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for hash, t := range m.tokens {
		if t.userID == userID && hash != currentHash {
			m.deleteSessionLocked(hash)
		}
	}
	return nil
}
func (m *MemoryStore) SavePushDeviceForSession(_ context.Context, userID, token, platform, hash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	t, ok := m.tokens[hash]
	now := time.Now().UTC()
	if !ok || t.userID != userID || !t.expires.After(now) || !t.session.CreatedAt.Add(MaxSessionLifetime).After(now) {
		return ErrNotFound
	}
	if m.pushDevices == nil {
		m.pushDevices = make(map[string]model.PushDevice)
	}
	m.pushDevices[token] = model.PushDevice{Token: token, UserID: userID, Platform: platform, SessionID: t.session.ID, UpdatedAt: now}
	return nil
}
func (p *PostgresStore) CheckSessionSchema(ctx context.Context) error {
	_, err := p.pool.Exec(ctx, `SELECT a.session_id,a.device_name,a.created_at,a.last_seen_at,d.session_id FROM auth_tokens a LEFT JOIN push_devices d ON d.session_id=a.session_id WHERE false`)
	if err != nil {
		return fmt.Errorf("account security schema unavailable; apply migrations/016_account_security.sql: %w", err)
	}
	return nil
}
