package httpapi

import (
	"net/http"
	"strings"
	"testing"
)

// Register through the same verified phone flow as the current Android client.
func registerVerifiedPhone(t *testing.T, h http.Handler, sender *fakeOTPSender, name, phone string) (map[string]any, string) {
	t.Helper()
	if code, body := doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": phone}, ""); code != http.StatusOK {
		t.Fatalf("request_code: %d %v", code, body)
	}
	normalized, err := NormalizePhone(phone)
	if err != nil {
		t.Fatal(err)
	}
	code, body := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": sender.code(normalized)}, "")
	if code != http.StatusOK {
		t.Fatalf("verify_code: %d %v", code, body)
	}
	token := body["token"].(string)
	if code, body := doReq(t, h, http.MethodPost, "/v1/account/profile", map[string]any{"name": name}, token); code != http.StatusOK {
		t.Fatalf("profile: %d %v", code, body)
	}
	code, account := doReq(t, h, http.MethodGet, "/v1/account", nil, token)
	if code != http.StatusOK {
		t.Fatalf("account: %d %v", code, account)
	}
	return account, token
}

func TestVerifiedPhoneAccountAndLogin(t *testing.T) {
	sender := &fakeOTPSender{}
	h, _ := newTestServerWith(t, sender)
	m, token := registerVerifiedPhone(t, h, sender, "Григорий", "8 (999) 123-45-67")
	if m["phone"] != "+79991234567" {
		t.Fatalf("номер не нормализован к E.164: %v", m)
	}
	if m["display_name"] != "Григорий" {
		t.Fatalf("имя не сохранено: %v", m)
	}
	username, _ := m["username"].(string)
	if !strings.HasPrefix(username, "u79991234567") {
		t.Fatalf("служебный username должен строиться из номера, получено %q", username)
	}

	// Re-verification must recover the same account, not create a duplicate.
	second, _ := registerVerifiedPhone(t, h, sender, "Григорий", "+7 999 123 45 67")
	if second["id"] != m["id"] {
		t.Fatalf("повторный вход создал другой аккаунт: %v", second)
	}

	code, _ := doReq(t, h, http.MethodPost, "/v1/account/profile", map[string]any{"name": ""}, token)
	if code != http.StatusBadRequest {
		t.Fatalf("без имени: ожидался 400, получен %d", code)
	}
	code, _ = doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": "123"}, "")
	if code != http.StatusBadRequest {
		t.Fatalf("невалидный номер: ожидался 400, получен %d", code)
	}
}

func TestDiscoverContacts(t *testing.T) {
	sender := &fakeOTPSender{}
	h, _ := newTestServerWith(t, sender)

	// Два пользователя с телефонами, один legacy без телефона.
	_, tokA := registerVerifiedPhone(t, h, sender, "Алиса", "+79991112233")
	mB, _ := registerVerifiedPhone(t, h, sender, "Борис", "+79994445566")
	if code, _ := register(t, h, newKeyBundle("legacy_user")); code != http.StatusCreated {
		t.Fatal("регистрация legacy-пользователя не удалась")
	}

	// В телефонной книге Алисы: Борис и неизвестный номер (дубль хэша игнорируется).
	unknownHash := PhoneHash("+75550001122")
	code, m := doReq(t, h, http.MethodPost, "/v1/contacts/discover", map[string]any{
		"hashes": []string{PhoneHash("+79994445566"), unknownHash, unknownHash},
	}, tokA)
	if code != http.StatusOK {
		t.Fatalf("discover: ожидался 200, получен %d (%v)", code, m)
	}
	matches, ok := m["matches"].([]any)
	if !ok || len(matches) != 1 {
		t.Fatalf("ожидался ровно один контакт, получено %v", m["matches"])
	}
	found := matches[0].(map[string]any)
	if found["phone"] != "+79994445566" || found["display_name"] != "Борис" || found["id"] != mB["id"] {
		t.Fatalf("неверные данные найденного контакта: %v", found)
	}

	// Без авторизации — 401.
	code, _ = doReq(t, h, http.MethodPost, "/v1/contacts/discover", map[string]any{"hashes": []string{unknownHash}}, "")
	if code != http.StatusUnauthorized {
		t.Fatalf("без токена: ожидался 401, получен %d", code)
	}
	// Невалидный хэш — 400.
	code, _ = doReq(t, h, http.MethodPost, "/v1/contacts/discover", map[string]any{"hashes": []string{"not-a-hash"}}, tokA)
	if code != http.StatusBadRequest {
		t.Fatalf("невалидный хэш: ожидался 400, получен %d", code)
	}
	// Пустой список — 200 с пустым результатом.
	code, m = doReq(t, h, http.MethodPost, "/v1/contacts/discover", map[string]any{"hashes": []string{}}, tokA)
	if code != http.StatusOK {
		t.Fatalf("пустой список: ожидался 200, получен %d", code)
	}
	if arr, _ := m["matches"].([]any); len(arr) != 0 {
		t.Fatalf("пустой запрос должен вернуть пустой matches, получено %v", m)
	}
}
