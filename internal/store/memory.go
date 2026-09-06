package store

import (
	"context"
	"sync"
	"time"

	"umbra/server/internal/model"
)

// MemoryStore — in-memory реализация Store для разработки и тестов.
// Не предназначен для продакшна (данные теряются при рестарте).
type MemoryStore struct {
	mu       sync.Mutex
	users    map[string]*model.User // id -> user
	byName   map[string]string      // username -> id
	prekeys  map[string][][]byte    // userID -> очередь одноразовых pre-keys
	tokens   map[string]tokenEntry  // tokenHash -> запись
	messages []*model.Message
	media    map[string]*model.Media
}

type tokenEntry struct {
	userID  string
	expires time.Time
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		users:   make(map[string]*model.User),
		byName:  make(map[string]string),
		prekeys: make(map[string][][]byte),
		tokens:  make(map[string]tokenEntry),
		media:   make(map[string]*model.Media),
	}
}

func (m *MemoryStore) CreateUser(_ context.Context, u *model.User) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.byName[u.Username]; ok {
		return ErrConflict
	}
	cp := *u
	cp.OneTimePrekeys = append([][]byte(nil), u.OneTimePrekeys...)
	m.users[u.ID] = &cp
	m.byName[u.Username] = u.ID
	m.prekeys[u.ID] = append([][]byte(nil), u.OneTimePrekeys...)
	return nil
}

func (m *MemoryStore) GetUserByUsername(_ context.Context, username string) (*model.User, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.byName[username]
	if !ok {
		return nil, ErrNotFound
	}
	return m.copyUser(m.users[id]), nil
}

func (m *MemoryStore) GetUserByID(_ context.Context, id string) (*model.User, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	u, ok := m.users[id]
	if !ok {
		return nil, ErrNotFound
	}
	return m.copyUser(u), nil
}

func (m *MemoryStore) TakeOneTimePrekey(_ context.Context, userID string) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	queue := m.prekeys[userID]
	if len(queue) == 0 {
		return nil, ErrNotFound
	}
	pk := queue[0]
	m.prekeys[userID] = queue[1:]
	return pk, nil
}

func (m *MemoryStore) PutToken(_ context.Context, tokenHash, userID string, expires time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.tokens[tokenHash] = tokenEntry{userID: userID, expires: expires}
	return nil
}

func (m *MemoryStore) GetUserIDByTokenHash(_ context.Context, tokenHash string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.tokens[tokenHash]
	if !ok {
		return "", ErrNotFound
	}
	if time.Now().After(e.expires) {
		delete(m.tokens, tokenHash)
		return "", ErrNotFound
	}
	return e.userID, nil
}

func (m *MemoryStore) DeleteToken(_ context.Context, tokenHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.tokens, tokenHash)
	return nil
}

func (m *MemoryStore) SaveMessage(_ context.Context, msg *model.Message) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	cp := *msg
	m.messages = append(m.messages, &cp)
	return nil
}

func (m *MemoryStore) ListMessages(_ context.Context, userID string, since time.Time) ([]*model.Message, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]*model.Message, 0)
	for _, msg := range m.messages {
		if msg.RecipientID == userID && msg.CreatedAt.After(since) {
			cp := *msg
			out = append(out, &cp)
		}
	}
	return out, nil
}

func (m *MemoryStore) SaveMedia(_ context.Context, media *model.Media) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.media[media.ID]; ok {
		return ErrConflict
	}
	if _, ok := m.users[media.OwnerID]; !ok {
		return ErrNotFound
	}
	cp := *media
	m.media[media.ID] = &cp
	return nil
}

func (m *MemoryStore) GetMedia(_ context.Context, id string) (*model.Media, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	media, ok := m.media[id]
	if !ok {
		return nil, ErrNotFound
	}
	cp := *media
	return &cp, nil
}

func (m *MemoryStore) Close() error { return nil }

func (m *MemoryStore) copyUser(u *model.User) *model.User {
	if u == nil {
		return nil
	}
	cp := *u
	cp.IdentityEd25519 = append([]byte(nil), u.IdentityEd25519...)
	cp.IdentityX25519 = append([]byte(nil), u.IdentityX25519...)
	cp.SignedPrekey = append([]byte(nil), u.SignedPrekey...)
	cp.SignedPrekeySig = append([]byte(nil), u.SignedPrekeySig...)
	return &cp
}
