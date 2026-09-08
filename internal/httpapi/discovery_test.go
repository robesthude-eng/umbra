package httpapi

import (
	"crypto/ed25519"
	"encoding/base64"
	"net/http"
	"strings"
	"testing"
)

// registerWithPhone регистрирует пользователя по имени + номеру телефона
// (без username — сервер должен сгенерировать служебный).
func registerWithPhone(t *testing.T, h http.Handler, name, phone string) (int, map[string]any, ed25519.PrivateKey) {
	t.Helper()
	kb := newKeyBundle("") // username перезаписываем ниже
	kb.reg["username"] = ""
	kb.reg["phone"] = phone
	kb.reg["name"] = name
	code, m := doReq(t, h, http.MethodPost, "/v1/register", kb.reg, "")
	return code, m, kb.privEd
}

// authenticateByPhone проходит challenge-response по номеру телефона.
func authenticateByPhone(t *testing.T, h http.Handler, phone string, priv ed25519.PrivateKey) string {
	t.Helper()
	code, m := doReq(t, h, http.MethodPost, "/v1/auth/challenge", map[string]any{"phone": phone}, "")
	if code != http.StatusOK {
		t.Fatalf("challenge по телефону: ожидался 200, получен %d (%v)", code, m)
	}
	ch, _ := m["challenge"].(string)
	sig := ed25519.Sign(priv, []byte(ch))
	code, m2 := doReq(t, h, http.MethodPost, "/v1/auth/verify", map[string]any{
		"phone":     phone,
		"challenge": ch,
		"signature": base64.StdEncoding.EncodeToString(sig),
	}, "")
	if code != http.StatusOK {
		t.Fatalf("verify по телефону: ожидался 200, получен %d (%v)", code, m2)
	}
	tok, _ := m2["token"].(string)
	return tok
}

func TestRegisterWithPhoneAndLogin(t *testing.T) {
	h := newTestServer(t)

	code, m, priv := registerWithPhone(t, h, "Григорий", "8 (999) 123-45-67")
	if code != http.StatusCreated {
		t.Fatalf("регистрация по телефону: ожидался 201, получен %d (%v)", code, m)
	}
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

	// Повторная регистрация того же номера — 409 phone already registered.
	code, m, _ = registerWithPhone(t, h, "Другой", "+7 999 123 45 67")
	if code != http.StatusConflict || m["error"] != "phone already registered" {
		t.Fatalf("повтор номера: ожидался 409 phone already registered, получено %d (%v)", code, m)
	}

	// Вход по номеру телефона: challenge + verify принимают phone.
	tok := authenticateByPhone(t, h, "+79991234567", priv)
	if tok == "" {
		t.Fatal("пустой токен после входа по телефону")
	}

	// Аккаунт отдаёт телефон и имя.
	code, m = doReq(t, h, http.MethodGet, "/v1/account", nil, tok)
	if code != http.StatusOK || m["phone"] != "+79991234567" || m["display_name"] != "Григорий" {
		t.Fatalf("account: ожидались phone и display_name, получено %d (%v)", code, m)
	}

	// Регистрация без имени — 400.
	code, _, _ = registerWithPhone(t, h, "", "+79990001122")
	if code != http.StatusBadRequest {
		t.Fatalf("без имени: ожидался 400, получен %d", code)
	}
	// Регистрация с невалидным номером — 400.
	code, _, _ = registerWithPhone(t, h, "Имя", "123")
	if code != http.StatusBadRequest {
		t.Fatalf("невалидный номер: ожидался 400, получен %d", code)
	}
	// challenge по неизвестному номеру — 404.
	code, _ = doReq(t, h, http.MethodPost, "/v1/auth/challenge", map[string]any{"phone": "+79990000000"}, "")
	if code != http.StatusNotFound {
		t.Fatalf("challenge по чужому номеру: ожидался 404, получен %d", code)
	}
}

func TestDiscoverContacts(t *testing.T) {
	h := newTestServer(t)

	// Два пользователя с телефонами, один legacy без телефона.
	code, _, privA := registerWithPhone(t, h, "Алиса", "+79991112233")
	if code != http.StatusCreated {
		t.Fatalf("регистрация A: %d", code)
	}
	code, mB, _ := registerWithPhone(t, h, "Борис", "+79994445566")
	if code != http.StatusCreated {
		t.Fatalf("регистрация B: %d", code)
	}
	if code, _ := register(t, h, newKeyBundle("legacy_user")); code != http.StatusCreated {
		t.Fatal("регистрация legacy-пользователя не удалась")
	}

	tokA := authenticateByPhone(t, h, "+79991112233", privA)

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
