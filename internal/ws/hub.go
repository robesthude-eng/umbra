// Package ws — WebSocket-хаб для realtime-доставки сообщений.
// Hub не знает и не хранит содержимого сообщений — только пересылает
// уже зашифрованные блобы подключённым клиентам по их userID.
package ws

import (
	"encoding/json"
	"sync"
)

// Hub хранит активные WebSocket-соединения в разрезе userID.
type Hub struct {
	mu         sync.RWMutex
	clients    map[string]map[*Client]bool
	register   chan *Client
	unregister chan *Client
}

func NewHub() *Hub {
	return &Hub{
		clients:    make(map[string]map[*Client]bool),
		register:   make(chan *Client, 256),
		unregister: make(chan *Client, 256),
	}
}

// Run — главный цикл хаба. Запускать в отдельной горутине.
func (h *Hub) Run() {
	for {
		select {
		case c := <-h.register:
			h.mu.Lock()
			if h.clients[c.userID] == nil {
				h.clients[c.userID] = make(map[*Client]bool)
			}
			h.clients[c.userID][c] = true
			h.mu.Unlock()
		case c := <-h.unregister:
			h.mu.Lock()
			if m := h.clients[c.userID]; m != nil {
				if m[c] {
					delete(m, c)
					close(c.send)
				}
				if len(m) == 0 {
					delete(h.clients, c.userID)
				}
			}
			h.mu.Unlock()
		}
	}
}

// Push отправляет payload (уже сериализованный в JSON) всем соединениям userID.
func (h *Hub) Push(userID string, payload any) {
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	h.mu.RLock()
	defer h.mu.RUnlock()
	for c := range h.clients[userID] {
		select {
		case c.send <- data:
		default:
			// Медленный клиент — пропускаем, чтобы не блокировать хаб.
			// Клиент догонит сообщения через REST-синхронизацию.
		}
	}
}

// Online сообщает, подключён ли пользователь в данный момент.
// Используется клиентами для статуса «онлайн» (метаданные не логируются).
func (h *Hub) Online(userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients[userID]) > 0
}

// Register подключает клиента к хабу.
func (h *Hub) Register(c *Client) { h.register <- c }

// Unregister отключает клиента.
func (h *Hub) Unregister(c *Client) { h.unregister <- c }
