package store

import (
	"context"
	"time"
)

// «Прочитано» в памяти: readerID -> peerID -> время. Карта создаётся лениво,
// чтобы старые конструкторы в тестах оставались валидными.

func (m *MemoryStore) SetReadCursor(_ context.Context, readerID, peerID string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[readerID]; !ok {
		return ErrNotFound
	}
	if _, ok := m.users[peerID]; !ok {
		return ErrNotFound
	}
	if at.IsZero() {
		at = time.Now()
	}
	at = at.UTC()
	if m.reads == nil {
		m.reads = make(map[string]map[string]time.Time)
	}
	if m.reads[readerID] == nil {
		m.reads[readerID] = make(map[string]time.Time)
	}
	// Курсор только растёт: гонка двух устройств не должна «разчитывать» переписку.
	if prev, ok := m.reads[readerID][peerID]; ok && prev.After(at) {
		return nil
	}
	m.reads[readerID][peerID] = at
	return nil
}

func (m *MemoryStore) GetReadCursor(_ context.Context, readerID, peerID string) (time.Time, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[readerID]; !ok {
		return time.Time{}, ErrNotFound
	}
	return m.reads[readerID][peerID], nil
}

// Прочтения в группах: chatID -> readerID -> время.

func (m *MemoryStore) SetChatRead(_ context.Context, chatID, readerID string, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.chats[chatID]; !ok {
		return ErrNotFound
	}
	if _, ok := m.users[readerID]; !ok {
		return ErrNotFound
	}
	if at.IsZero() {
		at = time.Now()
	}
	at = at.UTC()
	if m.chatReads == nil {
		m.chatReads = make(map[string]map[string]time.Time)
	}
	if m.chatReads[chatID] == nil {
		m.chatReads[chatID] = make(map[string]time.Time)
	}
	if prev, ok := m.chatReads[chatID][readerID]; ok && prev.After(at) {
		return nil
	}
	m.chatReads[chatID][readerID] = at
	return nil
}

func (m *MemoryStore) ListChatReads(_ context.Context, chatID string) (map[string]time.Time, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.chats[chatID]; !ok {
		return nil, ErrNotFound
	}
	// Копия: вызывающий код не должен видеть внутреннюю карту без замка.
	out := make(map[string]time.Time, len(m.chatReads[chatID]))
	for id, at := range m.chatReads[chatID] {
		out[id] = at
	}
	return out, nil
}
