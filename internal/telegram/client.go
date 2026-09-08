// Package telegram — минимальный клиент Bot API для доставки OTP-кодов Umbra
// и привязки «номер телефона ↔ Telegram-чат» (члены семьи один раз пишут боту
// свой номер, и коды приходят именно им).
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
	http  *http.Client
}

func NewClient(token string) *Client {
	return &Client{
		token: token,
		base:  "https://api.telegram.org/bot" + token,
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

// update — часть схемы getUpdates (достаточная для привязки номеров).
type update struct {
	UpdateID int64 `json:"update_id"`
	Message  *struct {
		Chat struct {
			ID int64 `json:"id"`
		} `json:"chat"`
		Text string `json:"text"`
	} `json:"message"`
}

// HandlePhoneBinding вызывается для каждого текстового сообщения, похожего на
// номер телефона (не команда). handle возвращает true, если номер принят —
// тогда бот ответит подтверждением.
type HandlePhoneBinding func(ctx context.Context, rawPhone string, chatID int64) (bool, error)

// StartPolling крутит getUpdates (long polling). Для одного бота должен быть
// ровно один слушатель: если номер/чат опрашивает ещё что-то (например, n8n),
// Telegram отдаёт конфликт — второй слушатель не должен запускаться.
func (c *Client) StartPolling(ctx context.Context, handle HandlePhoneBinding, senderName string) {
	offset := int64(0)
	greeted := make(map[int64]bool)
	for {
		if err := ctx.Err(); err != nil {
			return
		}
		body, err := c.post(ctx, "getUpdates", map[string]any{
			"offset":  offset,
			"timeout": 25,
		})
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("telegram (%s): getUpdates: %v", senderName, err)
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
			log.Printf("telegram (%s): bad update payload: %v", senderName, err)
			continue
		}
		for _, u := range resp.Result {
			offset = u.UpdateID + 1
			if u.Message == nil {
				continue
			}
			chatID := u.Message.Chat.ID
			text := strings.TrimSpace(u.Message.Text)
			if text == "" || strings.HasPrefix(text, "/") {
				if text == "/start" && !greeted[chatID] {
					greeted[chatID] = true
					_ = c.SendMessage(ctx, chatID, "Привет! Это бот Umbra — код подтверждения при регистрации и входе.\n\nОтправьте ваш номер телефона (например 79991234567), чтобы привязать его к этому чату.")
				}
				continue
			}
			ok, err := handle(ctx, text, chatID)
			if err != nil {
				log.Printf("telegram (%s): binding error: %v", senderName, err)
				_ = c.SendMessage(ctx, chatID, "Не получилось привязать номер. Попробуйте ещё раз.")
				continue
			}
			if ok {
				_ = c.SendMessage(ctx, chatID, "Номер привязан ✅ Теперь можно регистрироваться/входить в Umbra с этого номера.")
			}
		}
	}
}
