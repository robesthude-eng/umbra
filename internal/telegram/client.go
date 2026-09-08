// Package telegram — минимальный клиент Bot API для доставки OTP-кодов Umbra.
//
// Сеть сервера может блокировать прямой доступ к api.telegram.org, поэтому клиент
// умеет ходить через Cloudflare Worker-релей: тогда base = https://<worker>.workers.dev,
// а каждый вызов сопровождается заголовком x-umbra-key (секрет релея).
package telegram

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"time"
)

type Client struct {
	token string
	base  string
	key   string
	http  *http.Client
}

// NewClient создаёт клиент. При base=="" используется прямой api.telegram.org.
// При base != "" (Worker-релей) во все запросы добавляется x-umbra-key.
func NewClient(token, base, key string) *Client {
	if base == "" {
		base = "https://api.telegram.org/bot" + token
	}
	return &Client{
		token: token,
		base:  strings.TrimSuffix(base, "/"),
		key:   key,
		http:  &http.Client{Timeout: 60 * time.Second},
	}
}

func (c *Client) post(ctx context.Context, method string, payload any) ([]byte, error) {
	buf, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/"+method, bytes.NewReader(buf))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	if c.key != "" {
		req.Header.Set("x-umbra-key", c.key)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("telegram %s: http %d: %s", method, resp.StatusCode, truncate(string(body), 300))
	}
	return body, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}

// SendMessage отправляет обычное сообщение в чат.
func (c *Client) SendMessage(ctx context.Context, chatID int64, text string) error {
	_, err := c.post(ctx, "sendMessage", map[string]any{
		"chat_id": chatID,
		"text":    text,
	})
	return err
}

// update — минимальная схема getUpdates.
type update struct {
	UpdateID int64 `json:"update_id"`
	Message  *struct {
		Chat struct {
			ID int64 `json:"id"`
		} `json:"chat"`
		Text string `json:"text"`
	} `json:"message"`
}

// HandleMessage вызывается на каждое текстовое сообщение боту. Возвращает
// ответное сообщение ("" = не отвечать).
type HandleMessage func(ctx context.Context, chatID int64, text string) (reply string)

// StartPolling крутит getUpdates КОРОТКИМИ запросами (timeout:0) с паузой между
// опросами. Long-polling (timeout 25с) ненадёжен через Cloudflare Worker-релей:
// соединение висит дольше лимита и клиент ловит таймауты. Короткий опрос раз в
// секунду достаточно для бота одного владельца.
// Для одного бота должен быть ровно один слушатель: если его опрашивает что-то
// ещё (например, n8n того же бота), Telegram вернёт конфликт — лишние опросы
// нужно отключить.
func (c *Client) StartPolling(ctx context.Context, handle HandleMessage, name string) {
	offset := int64(0)
	greeted := make(map[int64]bool)
	for {
		if err := ctx.Err(); err != nil {
			return
		}
		body, err := c.post(ctx, "getUpdates", map[string]any{
			"offset":  offset,
			"timeout": 0,
		})
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("telegram (%s): getUpdates: %v", name, err)
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
			}
			continue
		}
		var resp struct {
			Result []update `json:"result"`
		}
		if err := json.Unmarshal(body, &resp); err != nil {
			log.Printf("telegram (%s): bad update payload: %v", name, err)
			continue
		}
		for _, u := range resp.Result {
			offset = u.UpdateID + 1
			if u.Message == nil {
				continue
			}
			chatID := u.Message.Chat.ID
			text := strings.TrimSpace(u.Message.Text)
			if text == "" {
				continue
			}
			reply := handle(ctx, chatID, text)
			if reply == "" && !greeted[chatID] {
				// Незнакомый чат: короткое приветствие, чтобы владелец понял бота.
				reply = "Umbra — код подтверждения. Напишите владельцу, если ждали код."
				greeted[chatID] = true
			}
			if reply != "" {
				if err := c.SendMessage(ctx, chatID, reply); err != nil {
					log.Printf("telegram (%s): send reply: %v", name, err)
				}
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}
