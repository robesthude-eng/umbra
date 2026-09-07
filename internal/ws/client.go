package ws

import (
	"time"

	"github.com/gorilla/websocket"
)

const (
	writeWait      = 10 * time.Second
	pongWait       = 60 * time.Second
	pingPeriod     = 50 * time.Second
	maxMessageSize = 512 * 1024 // 512 KiB — клиенты не должны слать крупное по WS
)

// Client — одно WebSocket-соединение одного пользователя.
type Client struct {
	hub    *Hub
	conn   *websocket.Conn
	userID string
	send   chan []byte
	authorized func() bool
}

func (c *Client) SetAuthorization(check func() bool) { c.authorized=check }

func NewClient(hub *Hub, conn *websocket.Conn, userID string) *Client {
	return &Client{hub: hub, conn: conn, userID: userID, send: make(chan []byte, 256)}
}

// ReadPump читает входящие кадры (ping/pong, close). Работает в отдельной горутине.
// Клиент в MVP ничего содержательного по WS не отправляет — приём сообщений
// идёт по REST, WS используется только для server push.
func (c *Client) ReadPump() {
	defer func() {
		c.hub.Unregister(c)
		_ = c.conn.Close()
	}()
	c.conn.SetReadLimit(maxMessageSize)
	_ = c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})
	for {
		if _, _, err := c.conn.ReadMessage(); err != nil {
			return
		}
	}
}

// WritePump пишет исходящие сообщения и держит соединение живым (ping).
func (c *Client) WritePump() {
	ticker := time.NewTicker(pingPeriod)
	authTicker := time.NewTicker(10*time.Second)
	defer func() {
		ticker.Stop()
		authTicker.Stop()
		_ = c.conn.Close()
	}()
	for {
		select {
		case <-authTicker.C:
			if c.authorized != nil && !c.authorized() { return }
		case msg, ok := <-c.send:
			if c.authorized != nil && !c.authorized() { return }
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = c.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-ticker.C:
			_ = c.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}
