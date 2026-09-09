package httpapi

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"umbra/server/internal/config"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

// testKeyBundle — сгенерированный на клиенте набор ключей + приватный ed25519
// для подписания challenge. Имитирует Android-клиент.
type testKeyBundle struct {
	privEd ed25519.PrivateKey
	reg    map[string]any
}

func newKeyBundle(username string) testKeyBundle {
	_, privEd, _ := ed25519.GenerateKey(rand.Reader)
	pubEd := privEd.Public().(ed25519.PublicKey)
	x, _ := ecdh.X25519().GenerateKey(rand.Reader)
	spk, _ := ecdh.X25519().GenerateKey(rand.Reader)
	spkSig := ed25519.Sign(privEd, spk.PublicKey().Bytes())
	var otks []string
	for i := 0; i < 5; i++ {
		k, _ := ecdh.X25519().GenerateKey(rand.Reader)
		otks = append(otks, base64.StdEncoding.EncodeToString(k.PublicKey().Bytes()))
	}
	return testKeyBundle{
		privEd: privEd,
		reg: map[string]any{
			"username":                username,
			"identity_ed25519":        base64.StdEncoding.EncodeToString(pubEd),
			"identity_x25519":         base64.StdEncoding.EncodeToString(x.PublicKey().Bytes()),
			"signed_prekey":           base64.StdEncoding.EncodeToString(spk.PublicKey().Bytes()),
			"signed_prekey_signature": base64.StdEncoding.EncodeToString(spkSig),
			"one_time_prekeys":        otks,
		},
	}
}

// newTestServer возвращает готовый http.Handler поверх in-memory хранилища.
func newTestServer(t *testing.T) http.Handler {
	t.Helper()
	h, _ := newTestServerWith(t, nil)
	return h
}

// newTestServerWith возвращает handler и его in-memory хранилище (для тестов,
// которым нужно заранее привязать данные, например OTP-привязку номера).
func newTestServerWith(t *testing.T, sender OTPSender) (http.Handler, *store.MemoryStore) {
	t.Helper()
	cfg := &config.Config{
		// These compatibility fixtures exercise username-only key clients.
		// Production defaults and phone-only rules have separate regression tests.
		AllowLegacyAuth: true,
		ListenAddr:      ":0",
		Store:           "memory",
		TokenTTL:        time.Hour,
		MaxMessageBytes: 1 << 20,
		TelegramChatID:  424242, // тестовая доставка кодов в «чат владельца»
	}
	st := store.NewMemoryStore()
	hub := ws.NewHub()
	srv := NewServerForMain(cfg, st, hub, nil, sender)
	return srv.Handler, st
}

// do выполняет HTTP-запрос к handler'у и возвращает код + декодированный JSON.
func doReq(t *testing.T, h http.Handler, method, path string, body any, token string) (int, map[string]any) {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("encode body: %v", err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	var m map[string]any
	_ = json.NewDecoder(rec.Body).Decode(&m)
	return rec.Code, m
}

func register(t *testing.T, h http.Handler, kb testKeyBundle) (int, map[string]any) {
	t.Helper()
	return doReq(t, h, http.MethodPost, "/v1/register", kb.reg, "")
}

func authenticate(t *testing.T, h http.Handler, username string, priv ed25519.PrivateKey) string {
	t.Helper()
	_, m := doReq(t, h, http.MethodPost, "/v1/auth/challenge", map[string]any{"username": username}, "")
	ch, _ := m["challenge"].(string)
	if ch == "" {
		t.Fatalf("пустой challenge в ответе: %v", m)
	}
	sig := ed25519.Sign(priv, []byte(ch))
	code, m2 := doReq(t, h, http.MethodPost, "/v1/auth/verify", map[string]any{
		"username":  username,
		"challenge": ch,
		"signature": base64.StdEncoding.EncodeToString(sig),
	}, "")
	if code != http.StatusOK {
		t.Fatalf("auth verify: ожидался 200, получен %d (%v)", code, m2)
	}
	tok, _ := m2["token"].(string)
	return tok
}

func TestHealthz(t *testing.T) {
	h := newTestServer(t)
	code, _ := doReq(t, h, http.MethodGet, "/healthz", nil, "")
	if code != http.StatusOK {
		t.Fatalf("ожидался 200, получен %d", code)
	}
}

func TestRegister(t *testing.T) {
	h := newTestServer(t)

	code, m := register(t, h, newKeyBundle("alice"))
	if code != http.StatusCreated {
		t.Fatalf("ожидался 201, получен %d (%v)", code, m)
	}
	if m["id"] == nil || m["username"] != "alice" {
		t.Fatalf("неверный ответ регистрации: %v", m)
	}

	// повтор — конфликт username
	code, _ = register(t, h, newKeyBundle("alice"))
	if code != http.StatusConflict {
		t.Fatalf("ожидался 409, получен %d", code)
	}

	// невалидный username
	bad := newKeyBundle("a")
	code, _ = register(t, h, bad)
	if code != http.StatusBadRequest {
		t.Fatalf("ожидался 400 для короткого username, получен %d", code)
	}
}

func TestAuthFlow(t *testing.T) {
	h := newTestServer(t)
	kb := newKeyBundle("alice")
	if code, _ := register(t, h, kb); code != http.StatusCreated {
		t.Fatalf("регистрация не удалась: %d", code)
	}

	tok := authenticate(t, h, "alice", kb.privEd)
	if tok == "" {
		t.Fatal("пустой токен после auth")
	}

	// неверная подпись — 401
	_, m := doReq(t, h, http.MethodPost, "/v1/auth/challenge", map[string]any{"username": "alice"}, "")
	ch, _ := m["challenge"].(string)
	_, wrong, _ := ed25519.GenerateKey(rand.Reader)
	badSig := ed25519.Sign(wrong, []byte(ch))
	code, _ := doReq(t, h, http.MethodPost, "/v1/auth/verify", map[string]any{
		"username":  "alice",
		"challenge": ch,
		"signature": base64.StdEncoding.EncodeToString(badSig),
	}, "")
	if code != http.StatusUnauthorized {
		t.Fatalf("ожидался 401 для неверной подписи, получен %d", code)
	}

	// повторное использование challenge — 401
	sig := ed25519.Sign(kb.privEd, []byte(ch))
	code, _ = doReq(t, h, http.MethodPost, "/v1/auth/verify", map[string]any{
		"username":  "alice",
		"challenge": ch,
		"signature": base64.StdEncoding.EncodeToString(sig),
	}, "")
	if code != http.StatusUnauthorized {
		t.Fatalf("повторный challenge должен дать 401, получен %d", code)
	}
}

func TestSendAndListMessages(t *testing.T) {
	h := newTestServer(t)
	alice := newKeyBundle("alice")
	bob := newKeyBundle("bob")

	if code, _ := register(t, h, alice); code != http.StatusCreated {
		t.Fatalf("регистрация alice: %d", code)
	}
	code, mBob := register(t, h, bob)
	if code != http.StatusCreated {
		t.Fatalf("регистрация bob: %d", code)
	}
	bobID, _ := mBob["id"].(string)

	tokA := authenticate(t, h, "alice", alice.privEd)
	tokB := authenticate(t, h, "bob", bob.privEd)

	ciphertext := base64.StdEncoding.EncodeToString([]byte("зашифрованное сообщение"))

	// отправка A -> B
	code, _ = doReq(t, h, http.MethodPost, "/v1/messages", map[string]any{
		"recipient_id": bobID,
		"ciphertext":   ciphertext,
	}, tokA)
	if code != http.StatusCreated {
		t.Fatalf("send: ожидался 201, получен %d", code)
	}

	// несуществующий получатель — 404
	code, _ = doReq(t, h, http.MethodPost, "/v1/messages", map[string]any{
		"recipient_id": "no-such-user",
		"ciphertext":   ciphertext,
	}, tokA)
	if code != http.StatusNotFound {
		t.Fatalf("send несуществующему: ожидался 404, получен %d", code)
	}

	// B получает сообщение
	code, m := doReq(t, h, http.MethodGet, "/v1/messages", nil, tokB)
	if code != http.StatusOK {
		t.Fatalf("list: ожидался 200, получен %d", code)
	}
	msgs, _ := m["messages"].([]any)
	if len(msgs) != 1 {
		t.Fatalf("ожидалось 1 сообщение, получено %d", len(msgs))
	}
	first, _ := msgs[0].(map[string]any)
	if first["ciphertext"] != ciphertext {
		t.Fatal("ciphertext не совпадает с отправленным")
	}

	// без токена — 401
	code, _ = doReq(t, h, http.MethodGet, "/v1/messages", nil, "")
	if code != http.StatusUnauthorized {
		t.Fatalf("list без токена: ожидался 401, получен %d", code)
	}

	// A видит собственное отправленное (облачная история обеих сторон, T1):
	// «как в Telegram» — после входа на новом устройстве подтягиваются и свои сообщения.
	code, m = doReq(t, h, http.MethodGet, "/v1/messages", nil, tokA)
	msgsA, _ := m["messages"].([]any)
	if code != http.StatusOK || len(msgsA) != 1 {
		t.Fatalf("A должен видеть свою историю (код %d, кол-во %d)", code, len(msgsA))
	}
	if a0, _ := msgsA[0].(map[string]any); a0["ciphertext"] != ciphertext {
		t.Fatal("A: своё отправленное не совпадает")
	}
}

func TestPrekeys(t *testing.T) {
	h := newTestServer(t)
	kb := newKeyBundle("alice")
	if code, _ := register(t, h, kb); code != http.StatusCreated {
		t.Fatalf("регистрация: %d", code)
	}

	code, m := doReq(t, h, http.MethodGet, "/v1/users/alice/prekeys", nil, "")
	if code != http.StatusOK {
		t.Fatalf("prekeys: ожидался 200, получен %d", code)
	}
	if m["id"] == nil || m["identity_x25519"] == nil || m["signed_prekey"] == nil {
		t.Fatalf("prekeys ответ неполный: %v", m)
	}

	// несуществующий пользователь
	code, _ = doReq(t, h, http.MethodGet, "/v1/users/nobody/prekeys", nil, "")
	if code != http.StatusNotFound {
		t.Fatalf("ожидался 404, получен %d", code)
	}
}
