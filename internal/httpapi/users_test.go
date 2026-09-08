package httpapi

import (
	"net/http"
	"testing"
)

// Регистрация двух номеров через OTP + карточка пользователя по id.
func TestGetUserCard(t *testing.T) {
	sender := &fakeOTPSender{}
	h, _ := newTestServerWith(t, sender)

	registerPhone := func(phone, name, username string) (id, token string) {
		doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": phone}, "")
		code, m := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": sender.code(phone)}, "")
		if code != http.StatusOK {
			t.Fatalf("verify %s: %d %v", phone, code, m)
		}
		acct := m["account"].(map[string]any)
		tok := m["token"].(string)
		if code, m := doReq(t, h, http.MethodPost, "/v1/account/profile",
			map[string]any{"name": name, "username": username, "last_name": "Фамилия"}, tok); code != http.StatusOK {
			t.Fatalf("profile %s: %d %v", phone, code, m)
		}
		return acct["id"].(string), tok
	}

	id1, tok1 := registerPhone("+79991112233", "Иван", "van")
	id2, _ := registerPhone("+79994445566", "Мария", "masha")

	// Карточка второго пользователя глазами первого.
	code, m := doReq(t, h, http.MethodGet, "/v1/users/"+id2, nil, tok1)
	if code != http.StatusOK {
		t.Fatalf("user card: %d %v", code, m)
	}
	if m["id"] != id2 || m["username"] != "masha" || m["display_name"] != "Мария" || m["last_name"] != "Фамилия" {
		t.Fatalf("неожиданная карточка: %v", m)
	}
	if m["avatar_media_id"] != nil && m["avatar_media_id"] != "" {
		t.Fatalf("аватар не задавался: %v", m)
	}

	// Самого себя тоже можно запросить.
	if code, m := doReq(t, h, http.MethodGet, "/v1/users/"+id1, nil, tok1); code != http.StatusOK || m["id"] != id1 {
		t.Fatalf("self card: %d %v", code, m)
	}

	// Несуществующий id — 404.
	if code, _ := doReq(t, h, http.MethodGet, "/v1/users/__nope__", nil, tok1); code != http.StatusNotFound {
		t.Fatalf("unknown user: %d", code)
	}

	// Без токена — 401.
	if code, _ := doReq(t, h, http.MethodGet, "/v1/users/"+id2, nil, ""); code != http.StatusUnauthorized {
		t.Fatalf("без авторизации: %d", code)
	}
}

func TestGetUserByUsername(t *testing.T) {
	sender := &fakeOTPSender{}
	h, _ := newTestServerWith(t, sender)
	doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": "+79991112233"}, "")
	code, m := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": "+79991112233", "code": sender.code("+79991112233")}, "")
	if code != http.StatusOK {
		t.Fatalf("verify: %d %v", code, m)
	}
	tok := m["token"].(string)
	doReq(t, h, http.MethodPost, "/v1/account/profile", map[string]any{"name": "Иван", "username": "van"}, tok)

	if code, m := doReq(t, h, http.MethodGet, "/v1/by-username/van", nil, tok); code != http.StatusOK {
		t.Fatalf("by-username: %d %v", code, m)
	} else if m["username"] != "van" || m["display_name"] != "Иван" {
		t.Fatalf("неожиданная карточка: %v", m)
	}
	if code, m := doReq(t, h, http.MethodGet, "/v1/by-username/nobody", nil, tok); code != http.StatusNotFound {
		t.Fatalf("неизвестный username: %d %v", code, m)
	}
}
