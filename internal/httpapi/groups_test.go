package httpapi

import (
	"encoding/base64"
	"net/http"
	"testing"
)

// setupUsers регистрирует и аутентифицирует трёх пользователей, возвращая
// их id и токены.
func setupUsers(t *testing.T, h http.Handler, names ...string) map[string]struct {
	ID    string
	Token string
} {
	t.Helper()
	out := make(map[string]struct {
		ID    string
		Token string
	})
	for _, n := range names {
		kb := newKeyBundle(n)
		code, m := register(t, h, kb)
		if code != http.StatusCreated {
			t.Fatalf("регистрация %s: %d", n, code)
		}
		id, _ := m["id"].(string)
		tok := authenticate(t, h, n, kb.privEd)
		out[n] = struct {
			ID    string
			Token string
		}{ID: id, Token: tok}
	}
	return out
}

func TestCreateGroupAndMembers(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob", "carol")

	// alice создаёт группу
	code, m := doReq(t, h, http.MethodPost, "/v1/groups", map[string]any{"title": "Тестовая группа"}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("создание группы: %d (%v)", code, m)
	}
	groupID, _ := m["id"].(string)
	if m["type"] != "group" {
		t.Fatalf("ожидался type=group, получен %v", m["type"])
	}

	// alice (owner) добавляет bob
	code, _ = doReq(t, h, http.MethodPost, "/v1/chats/"+groupID+"/members", map[string]any{"user_id": u["bob"].ID}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("добавление bob: %d", code)
	}

	// bob (member) не может добавить carol в группу — 403
	code, _ = doReq(t, h, http.MethodPost, "/v1/chats/"+groupID+"/members", map[string]any{"user_id": u["carol"].ID}, u["bob"].Token)
	if code != http.StatusForbidden {
		t.Fatalf("member не должен добавлять участников, получен %d", code)
	}

	// carol не участник — не видит список участников (404)
	code, _ = doReq(t, h, http.MethodGet, "/v1/chats/"+groupID+"/members", nil, u["carol"].Token)
	if code != http.StatusNotFound {
		t.Fatalf("неучастник должен получить 404, получен %d", code)
	}

	// alice видит участников (2: alice + bob)
	code, m = doReq(t, h, http.MethodGet, "/v1/chats/"+groupID+"/members", nil, u["alice"].Token)
	if code != http.StatusOK {
		t.Fatalf("список участников: %d", code)
	}
	members, _ := m["members"].([]any)
	if len(members) != 2 {
		t.Fatalf("ожидалось 2 участника, получено %d", len(members))
	}

	// alice не может удалить себя (owner) — 403
	code, _ = doReq(t, h, http.MethodDelete, "/v1/chats/"+groupID+"/members/"+u["alice"].ID, nil, u["alice"].Token)
	if code != http.StatusForbidden {
		t.Fatalf("удаление owner должно дать 403, получен %d", code)
	}

	// список чатов alice
	code, m = doReq(t, h, http.MethodGet, "/v1/chats", nil, u["alice"].Token)
	chats, _ := m["chats"].([]any)
	if code != http.StatusOK || len(chats) != 1 {
		t.Fatalf("список чатов: %d, len=%d", code, len(chats))
	}
}

func TestGroupMessages(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	_, m := doReq(t, h, http.MethodPost, "/v1/groups", map[string]any{"title": "Группа"}, u["alice"].Token)
	groupID, _ := m["id"].(string)
	doReq(t, h, http.MethodPost, "/v1/chats/"+groupID+"/members", map[string]any{"user_id": u["bob"].ID}, u["alice"].Token)

	ciphertext := base64.StdEncoding.EncodeToString([]byte("зашифрованное групповое сообщение"))

	// alice отправляет сообщение в группу
	code, m := doReq(t, h, http.MethodPost, "/v1/chats/"+groupID+"/messages", map[string]any{"ciphertext": ciphertext}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("отправка группового сообщения: %d (%v)", code, m)
	}
	if m["chat_id"] != groupID {
		t.Fatalf("ожидался chat_id=%s, получен %v", groupID, m["chat_id"])
	}

	// bob получает сообщение через ListMessages
	code, m = doReq(t, h, http.MethodGet, "/v1/messages", nil, u["bob"].Token)
	if code != http.StatusOK {
		t.Fatalf("list messages: %d", code)
	}
	msgs, _ := m["messages"].([]any)
	if len(msgs) != 1 {
		t.Fatalf("bob должен получить 1 сообщение, получено %d", len(msgs))
	}
	first, _ := msgs[0].(map[string]any)
	if first["chat_id"] != groupID || first["ciphertext"] != ciphertext {
		t.Fatalf("неверное сообщение: %v", first)
	}

	// неучастник не может отправить в группу — 404
	carol := setupUsers(t, h, "carol")
	code, _ = doReq(t, h, http.MethodPost, "/v1/chats/"+groupID+"/messages", map[string]any{"ciphertext": ciphertext}, carol["carol"].Token)
	if code != http.StatusNotFound {
		t.Fatalf("неучастник должен получить 404, получен %d", code)
	}
}

func TestChannelWritePermissions(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	_, m := doReq(t, h, http.MethodPost, "/v1/channels", map[string]any{"title": "Канал"}, u["alice"].Token)
	chanID, _ := m["id"].(string)

	// bob подписывается сам (в канал — самоподписка)
	code, _ := doReq(t, h, http.MethodPost, "/v1/chats/"+chanID+"/members", map[string]any{"user_id": u["bob"].ID}, u["bob"].Token)
	if code != http.StatusCreated {
		t.Fatalf("самоподписка в канал: %d", code)
	}

	ciphertext := base64.StdEncoding.EncodeToString([]byte("пост в канал"))

	// bob (member) не может писать в канал — 403
	code, _ = doReq(t, h, http.MethodPost, "/v1/chats/"+chanID+"/messages", map[string]any{"ciphertext": ciphertext}, u["bob"].Token)
	if code != http.StatusForbidden {
		t.Fatalf("member не должен писать в канал, получен %d", code)
	}

	// alice (owner) пишет — 201
	code, _ = doReq(t, h, http.MethodPost, "/v1/chats/"+chanID+"/messages", map[string]any{"ciphertext": ciphertext}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("owner должен писать в канал, получен %d", code)
	}
}

func TestContacts(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	// alice добавляет bob в контакты
	code, _ := doReq(t, h, http.MethodPost, "/v1/contacts", map[string]any{"contact_id": u["bob"].ID}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("добавление контакта: %d", code)
	}

	// несуществующий контакт — 404
	code, _ = doReq(t, h, http.MethodPost, "/v1/contacts", map[string]any{"contact_id": "no-such-user"}, u["alice"].Token)
	if code != http.StatusNotFound {
		t.Fatalf("ожидался 404, получен %d", code)
	}

	// список контактов
	code, m := doReq(t, h, http.MethodGet, "/v1/contacts", nil, u["alice"].Token)
	contacts, _ := m["contacts"].([]any)
	if code != http.StatusOK || len(contacts) != 1 {
		t.Fatalf("список контактов: %d, len=%d", code, len(contacts))
	}

	// у bob нет контактов
	code, m = doReq(t, h, http.MethodGet, "/v1/contacts", nil, u["bob"].Token)
	contactsBob, _ := m["contacts"].([]any)
	if code != http.StatusOK || len(contactsBob) != 0 {
		t.Fatalf("у bob не должно быть контактов, len=%d", len(contactsBob))
	}
}

func TestTyping(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	_, m := doReq(t, h, http.MethodPost, "/v1/groups", map[string]any{"title": "Группа"}, u["alice"].Token)
	groupID, _ := m["id"].(string)
	doReq(t, h, http.MethodPost, "/v1/chats/"+groupID+"/members", map[string]any{"user_id": u["bob"].ID}, u["alice"].Token)

	// bob отмечает «печатает»
	code, _ := doReq(t, h, http.MethodPost, "/v1/chats/"+groupID+"/typing", nil, u["bob"].Token)
	if code != http.StatusOK {
		t.Fatalf("typing mark: %d", code)
	}

	// alice видит, что bob печатает
	code, m = doReq(t, h, http.MethodGet, "/v1/chats/"+groupID+"/typing", nil, u["alice"].Token)
	typing, _ := m["typing"].([]any)
	if code != http.StatusOK || len(typing) != 1 {
		t.Fatalf("ожидался 1 печатающий, код %d, len=%d", code, len(typing))
	}
	if typing[0] != u["bob"].ID {
		t.Fatalf("ожидался %s, получен %v", u["bob"].ID, typing[0])
	}

	// неучастник не видит typing — 404
	carol := setupUsers(t, h, "carol")
	code, _ = doReq(t, h, http.MethodGet, "/v1/chats/"+groupID+"/typing", nil, carol["carol"].Token)
	if code != http.StatusNotFound {
		t.Fatalf("неучастник должен получить 404, получен %d", code)
	}
}
