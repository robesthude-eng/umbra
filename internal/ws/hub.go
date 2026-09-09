// Package ws доставляет непрозрачные сообщения всем подключённым устройствам.
package ws

import (
	"encoding/json"
	"sync"
)

type Hub struct {
	mu      sync.RWMutex
	clients map[string]map[*Client]bool
	done    chan struct{}
	closed  bool
}

func NewHub() *Hub {
	return &Hub{clients: make(map[string]map[*Client]bool), done: make(chan struct{})}
}

// Run оставлен для совместимости. Регистрация теперь синхронная: push сразу
// после WebSocket-handshake не теряется в очереди регистрации.
func (h *Hub) Run() { <-h.done }

func (h *Hub) Register(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		close(c.send)
		_ = c.conn.Close()
		return
	}
	if h.clients[c.userID] == nil {
		h.clients[c.userID] = make(map[*Client]bool)
	}
	h.clients[c.userID][c] = true
}

func (h *Hub) Unregister(c *Client) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.remove(c)
}

func (h *Hub) remove(c *Client) {
	if h.clients[c.userID][c] {
		delete(h.clients[c.userID], c)
		close(c.send)
		if len(h.clients[c.userID]) == 0 {
			delete(h.clients, c.userID)
		}
	}
}

func (h *Hub) Push(userID string, payload any) {
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients[userID] {
		select {
		case c.send <- data:
		default:
			// Пропуск явно превращаем в разрыв: клиент пересинхронизируется через REST.
			h.remove(c)
			_ = c.conn.Close()
		}
	}
}

func (h *Hub) Online(userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[userID]) != 0
}

func (h *Hub) DisconnectUser(userID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients[userID] {
		h.remove(c)
		_ = c.conn.Close()
	}
}

func (h *Hub) Close() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closed {
		return
	}
	h.closed = true
	for _, clients := range h.clients {
		for c := range clients {
			h.remove(c)
			_ = c.conn.Close()
		}
	}
	close(h.done)
}
