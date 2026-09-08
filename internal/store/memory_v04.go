package store

import (
	"context"
)

func (m *MemoryStore) BindTelegram(_ context.Context, phone string, tgChatID int64) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.tgChat[phone] = tgChatID
	return nil
}

func (m *MemoryStore) TelegramChatForPhone(_ context.Context, phone string) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	chatID, ok := m.tgChat[phone]
	if !ok {
		return 0, ErrNotFound
	}
	return chatID, nil
}

func (m *MemoryStore) UpdateAccountProfile(_ context.Context, userID, username, displayName string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	u, ok := m.users[userID]
	if !ok {
		return ErrNotFound
	}
	if prev, exists := m.byName[username]; exists && prev != userID {
		return ErrConflict
	}
	delete(m.byName, u.Username)
	u.Username = username
	u.DisplayName = displayName
	m.byName[username] = userID
	return nil
}

func (m *MemoryStore) SetAvatar(_ context.Context, userID, mediaID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[userID]; !ok {
		return ErrNotFound
	}
	m.avatars[userID] = mediaID
	return nil
}

func (m *MemoryStore) GetAvatar(_ context.Context, userID string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	mediaID, ok := m.avatars[userID]
	if !ok {
		return "", ErrNotFound
	}
	return mediaID, nil
}
