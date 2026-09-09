package store

import (
	"context"
	"sort"
	"time"

	"umbra/server/internal/model"
)

// SavePushDevice — идемпотентная привязка токена FCM к пользователю.
// Если токен был у другого аккаунта (сменили пользователя на том же
// телефоне), запись переезжает — иначе уведомления ушли бы не туда.
func (m *MemoryStore) SavePushDevice(_ context.Context, userID, token, platform string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.users[userID]; !ok {
		return ErrNotFound
	}
	if m.pushDevices == nil {
		m.pushDevices = make(map[string]model.PushDevice)
	}
	m.pushDevices[token] = model.PushDevice{
		Token:     token,
		UserID:    userID,
		Platform:  platform,
		UpdatedAt: time.Now().UTC(),
	}
	return nil
}

// ListPushDevices возвращает все устройства пользователя (у семьи бывает
// несколько телефонов на один аккаунт), свежие — первыми.
func (m *MemoryStore) ListPushDevices(_ context.Context, userID string) ([]model.PushDevice, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	out := make([]model.PushDevice, 0, len(m.pushDevices))
	for _, d := range m.pushDevices {
		if d.UserID == userID {
			out = append(out, d)
		}
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].UpdatedAt.Equal(out[j].UpdatedAt) {
			return out[i].Token < out[j].Token
		}
		return out[i].UpdatedAt.After(out[j].UpdatedAt)
	})
	return out, nil
}

// DeletePushDevice убирает токен: выход из аккаунта или ответ FCM
// «UNREGISTERED» (приложение удалили).
func (m *MemoryStore) DeletePushDevice(_ context.Context, token string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if _, ok := m.pushDevices[token]; !ok {
		return ErrNotFound
	}
	delete(m.pushDevices, token)
	return nil
}

// dropPushDevicesForUser вызывается из DeleteUser под уже взятым mu.
func (m *MemoryStore) dropPushDevicesForUser(userID string) {
	for token, d := range m.pushDevices {
		if d.UserID == userID {
			delete(m.pushDevices, token)
		}
	}
}
