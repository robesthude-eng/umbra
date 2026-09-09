package store

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/json"
	"sort"
	"time"

	"umbra/server/internal/model"
)

type messageReceipt struct {
	hash    [32]byte
	id      string
	created time.Time
	expires *time.Time
}

func (m *MemoryStore) OneTimePrekeyCount(_ context.Context, userID string) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[userID]; !ok {
		return 0, ErrNotFound
	}
	return len(m.prekeys[userID]), nil
}

func messageRequestHash(m *model.Message) [32]byte {
	// Хэш ciphertext и адресации, не открытый текст. JSON исключает неоднозначную склейку.
	b, _ := json.Marshal([]any{m.RecipientID, m.ChatID, m.Ciphertext, m.ExpiresIn})
	return sha256.Sum256(b)
}

func sameCurveIdentity(a, b []byte) bool {
	if len(a) == 33 && a[0] == 5 {
		a = a[1:]
	}
	if len(b) == 33 && b[0] == 5 {
		b = b[1:]
	}
	return bytes.Equal(a, b)
}

func (m *MemoryStore) TakePrekeyBundle(_ context.Context, username string) (*model.User, []byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.byName[username]
	if !ok {
		return nil, nil, ErrNotFound
	}
	u := m.copyUser(m.users[id])
	u.OneTimePrekeys = nil
	var pk []byte
	if q := m.prekeys[id]; len(q) != 0 {
		pk = append([]byte(nil), q[0]...)
		m.prekeys[id] = q[1:]
	}
	return u, pk, nil
}

func (m *MemoryStore) UpdateKeys(_ context.Context, userID string, keys *model.User) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	u, ok := m.users[userID]
	if !ok {
		return ErrNotFound
	}
	if !sameCurveIdentity(u.IdentityX25519, keys.IdentityX25519) {
		return ErrConflict
	}
	if u.KeyBundleID == keys.KeyBundleID && keys.KeyBundleID != "" {
		return nil
	}
	u.KeyBundleID = keys.KeyBundleID
	u.IdentityX25519 = append([]byte(nil), keys.IdentityX25519...)
	u.KeyVersion, u.RegistrationID, u.SignedPrekeyID = keys.KeyVersion, keys.RegistrationID, keys.SignedPrekeyID
	u.SignedPrekey = append([]byte(nil), keys.SignedPrekey...)
	u.SignedPrekeySig = append([]byte(nil), keys.SignedPrekeySig...)
	m.prekeys[userID] = make([][]byte, len(keys.OneTimePrekeys))
	for i, pk := range keys.OneTimePrekeys {
		m.prekeys[userID][i] = append([]byte(nil), pk...)
	}
	return nil
}

func (m *MemoryStore) ListMessagesPage(ctx context.Context, userID string, since time.Time, afterID string, limit int) ([]*model.Message, error) {
	all, err := m.ListMessages(ctx, userID, since.Add(-time.Nanosecond))
	if err != nil {
		return nil, err
	}
	sort.Slice(all, func(i, j int) bool {
		if all[i].CreatedAt.Equal(all[j].CreatedAt) {
			return all[i].ID < all[j].ID
		}
		return all[i].CreatedAt.Before(all[j].CreatedAt)
	})
	out := make([]*model.Message, 0)
	for _, msg := range all {
		if msg.CreatedAt.Equal(since) && (afterID == "" || msg.ID <= afterID) {
			continue
		}
		out = append(out, msg)
		if len(out) == limit {
			break
		}
	}
	return out, nil
}

func (m *MemoryStore) SaveMediaWithQuota(_ context.Context, media *model.Media, limit int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.media[media.ID]; ok {
		return ErrConflict
	}
	if _, ok := m.users[media.OwnerID]; !ok {
		return ErrNotFound
	}
	if media.Size < 0 {
		return ErrQuota
	}
	if limit > 0 {
		var used int64
		for _, item := range m.media {
			if item.OwnerID == media.OwnerID {
				if item.Size > limit-used {
					return ErrQuota
				}
				used += item.Size
			}
		}
		if media.Size > limit-used {
			return ErrQuota
		}
	}
	cp := *media
	m.media[media.ID] = &cp
	return nil
}

func (m *MemoryStore) LockBlobs(ctx context.Context, exclusive bool) (func(), error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if exclusive {
		m.blobMu.Lock()
		return m.blobMu.Unlock, nil
	}
	m.blobMu.RLock()
	return m.blobMu.RUnlock, nil
}

func (m *MemoryStore) PendingBlobDeletes(_ context.Context) ([]string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	ids := make([]string, 0, len(m.deletions))
	for id := range m.deletions {
		ids = append(ids, id)
		if len(ids) == 500 {
			break
		}
	}
	return ids, nil
}

func (m *MemoryStore) CompleteBlobDelete(_ context.Context, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.deletions, id)
	return nil
}

func (m *MemoryStore) PurgeExpired(_ context.Context, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	kept := make([]*model.Message, 0, len(m.messages))
	for _, msg := range m.messages {
		if msg.ExpiresAt == nil || msg.ExpiresAt.After(now) {
			kept = append(kept, msg)
		}
	}
	m.messages = kept
	for key, value := range m.tokens {
		if !value.expires.After(now) {
			delete(m.tokens, key)
		}
	}
	for key, receipt := range m.receipts {
		if receipt.created.Before(now.Add(-30 * 24 * time.Hour)) {
			delete(m.receipts, key)
		}
	}
	return nil
}
