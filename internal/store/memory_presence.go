package store

import (
	"context"
	"time"
)

// «Был(а) в сети» в памяти. Карты создаются лениво: конструктор старых
// тестов остаётся валидным без изменений.
func (m *MemoryStore) TouchPresence(_ context.Context, userID string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[userID]; !ok {
		return ErrNotFound
	}
	if m.presence == nil {
		m.presence = make(map[string]time.Time)
	}
	if at.IsZero() {
		at = time.Now()
	}
	m.presence[userID] = at.UTC()
	return nil
}

func (m *MemoryStore) GetPresence(_ context.Context, userID string) (time.Time, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[userID]; !ok {
		return time.Time{}, false, ErrNotFound
	}
	return m.presence[userID], m.presenceHidden[userID], nil
}

func (m *MemoryStore) SetPresenceHidden(_ context.Context, userID string, hidden bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[userID]; !ok {
		return ErrNotFound
	}
	if m.presenceHidden == nil {
		m.presenceHidden = make(map[string]bool)
	}
	if hidden {
		m.presenceHidden[userID] = true
	} else {
		delete(m.presenceHidden, userID)
	}
	return nil
}
