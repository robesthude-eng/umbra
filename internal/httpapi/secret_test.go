package httpapi

import (
	"encoding/base64"
	"net/http"
	"testing"
	"time"
)

// waitForExpiry ждёт истечения self-destruct таймера (1 секунда + запас).
func waitForExpiry(t *testing.T) {
	t.Helper()
	time.Sleep(1200 * time.Millisecond)
}

func TestSecretChatSelfDestruct(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	ciphertext := base64.StdEncoding.EncodeToString([]byte("секретное сообщение"))

	// alice отправляет bob сообщение с таймером 1 секунда
	code, m := doReq(t, h, http.MethodPost, "/v1/messages", map[string]any{
		"recipient_id": u["bob"].ID,
		"ciphertext":   ciphertext,
		"expires_in":   1,
	}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("send secret: %d (%v)", code, m)
	}
	if m["expires_at"] == nil {
		t.Fatalf("ожидался expires_at, получен nil")
	}

	// сразу после отправки bob видит сообщение
	code, m = doReq(t, h, http.MethodGet, "/v1/messages", nil, u["bob"].Token)
	msgs, _ := m["messages"].([]any)
	if code != http.StatusOK || len(msgs) != 1 {
		t.Fatalf("bob должен видеть сообщение сразу: %d, len=%d", code, len(msgs))
	}

	// ждём истечения таймера
	waitForExpiry(t)

	// после истечения bob НЕ видит сообщение
	code, m = doReq(t, h, http.MethodGet, "/v1/messages", nil, u["bob"].Token)
	msgs, _ = m["messages"].([]any)
	if code != http.StatusOK || len(msgs) != 0 {
		t.Fatalf("после истечения сообщение не должно отдаваться: len=%d", len(msgs))
	}
}

func TestSecretChatGroupSelfDestruct(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	_, m := doReq(t, h, http.MethodPost, "/v1/groups", map[string]any{"title": "Секретная группа"}, u["alice"].Token)
	gid, _ := m["id"].(string)
	doReq(t, h, http.MethodPost, "/v1/chats/"+gid+"/members", map[string]any{"user_id": u["bob"].ID}, u["alice"].Token)

	ciphertext := base64.StdEncoding.EncodeToString([]byte("секрет в группе"))
	code, _ := doReq(t, h, http.MethodPost, "/v1/chats/"+gid+"/messages", map[string]any{
		"ciphertext": ciphertext,
		"expires_in": 1,
	}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("send group secret: %d", code)
	}

	waitForExpiry(t)

	code, m = doReq(t, h, http.MethodGet, "/v1/messages", nil, u["bob"].Token)
	msgs, _ := m["messages"].([]any)
	if code != http.StatusOK || len(msgs) != 0 {
		t.Fatalf("после истечения групповое сообщение не должно отдаваться: len=%d", len(msgs))
	}
}

func TestBurnAccount(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	// alice шлёт сообщение bob'у
	ciphertext := base64.StdEncoding.EncodeToString([]byte("перед сжиганием"))
	doReq(t, h, http.MethodPost, "/v1/messages", map[string]any{
		"recipient_id": u["bob"].ID, "ciphertext": ciphertext,
	}, u["alice"].Token)

	// alice добавляет bob в контакты
	doReq(t, h, http.MethodPost, "/v1/contacts", map[string]any{"contact_id": u["bob"].ID}, u["alice"].Token)

	// проверяем аккаунт alice
	code, m := doReq(t, h, http.MethodGet, "/v1/account", nil, u["alice"].Token)
	if code != http.StatusOK || m["username"] != "alice" {
		t.Fatalf("get account: %d %v", code, m)
	}

	// alice сжигает аккаунт
	code, _ = doReq(t, h, http.MethodPost, "/v1/account/burn", nil, u["alice"].Token)
	if code != http.StatusOK {
		t.Fatalf("burn account: %d", code)
	}

	// токен alice больше не валиден — 401
	code, _ = doReq(t, h, http.MethodGet, "/v1/account", nil, u["alice"].Token)
	if code != http.StatusUnauthorized {
		t.Fatalf("после сжигания токен должен дать 401, получен %d", code)
	}

	// username alice освободился — можно зарегистрировать заново
	kb := newKeyBundle("alice")
	code, _ = register(t, h, kb)
	if code != http.StatusCreated {
		t.Fatalf("username должен освободиться: %d", code)
	}

	// bob больше не видит сообщений от alice (отправленные удалены)
	code, m = doReq(t, h, http.MethodGet, "/v1/messages", nil, u["bob"].Token)
	msgs, _ := m["messages"].([]any)
	if code != http.StatusOK || len(msgs) != 0 {
		t.Fatalf("сообщения сожжённого пользователя должны быть удалены: len=%d", len(msgs))
	}
}
