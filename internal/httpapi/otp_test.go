package httpapi

import (
	"context"
	"net/http"
	"sync"
	"testing"

	"umbra/server/internal/store"
)

// fakeOTPSender запоминает отправленные коды (тест достаёт код из него).
type fakeOTPSender struct {
	mu   sync.Mutex
	sent map[string]string // phone -> code
}

func (f *fakeOTPSender) SendCode(_ context.Context, phone string, _ int64, code string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.sent == nil {
		f.sent = make(map[string]string)
	}
	f.sent[phone] = code
	return nil
}

func (f *fakeOTPSender) code(phone string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.sent[phone]
}

func bindPhone(t *testing.T, st *store.MemoryStore, phone string) {
	t.Helper()
	if err := st.BindTelegram(context.Background(), phone, 12345); err != nil {
		t.Fatal(err)
	}
}

func TestOTPRegisterAndLoginByPhone(t *testing.T) {
	sender := &fakeOTPSender{}
	h, st := newTestServerWith(t, sender)
	const phone = "+79001112233"
	bindPhone(t, st, phone)

	// Регистрация: запрос кода, затем verify создаёт аккаунт.
	if code, m := doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": phone}, ""); code != http.StatusOK {
		t.Fatalf("request_code: %d %v", code, m)
	}
	otp := sender.code(phone)
	if otp == "" {
		t.Fatal("код не отправлен сендеру")
	}
	code, m := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": otp}, "")
	if code != http.StatusOK {
		t.Fatalf("verify_code (новый аккаунт): %d %v", code, m)
	}
	if m["new_account"] != true {
		t.Fatalf("ожидался новый аккаунт: %v", m)
	}
	if m["profile_complete"] != false {
		t.Fatalf("профиль нового аккаунта не должен быть заполнен: %v", m)
	}
	acct := m["account"].(map[string]any)
	if acct["phone"] != phone {
		t.Fatalf("phone в аккаунте: %v", acct["phone"])
	}
	token := m["token"].(string)

	// Завершение регистрации: имя (обязательно) и @username.
	if code, _ := doReq(t, h, http.MethodPost, "/v1/account/profile", map[string]any{"name": ""}, token); code != http.StatusBadRequest {
		t.Fatalf("пустое имя должно отклоняться: %d", code)
	}
	if code, m := doReq(t, h, http.MethodPost, "/v1/account/profile", map[string]any{"name": "Иван", "username": "test"}, token); code != http.StatusOK {
		t.Fatalf("заполнение профиля: %d %v", code, m)
	}

	// Повторный verify того же номера — это вход (new_account=false), профиль полный.
	doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": phone}, "")
	code, m = doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": sender.code(phone)}, "")
	if code != http.StatusOK {
		t.Fatalf("verify_code (вход): %d %v", code, m)
	}
	if m["new_account"] != false {
		t.Fatalf("повторный вход не должен создавать аккаунт: %v", m)
	}
	if m["profile_complete"] != true {
		t.Fatalf("профиль должен быть заполнен: %v", m)
	}
	acct = m["account"].(map[string]any)
	if acct["username"] != "test" || acct["display_name"] != "Иван" {
		t.Fatalf("профиль не восстановился: %v", acct)
	}
}

func TestOTPWrongCodeAndOneTime(t *testing.T) {
	sender := &fakeOTPSender{}
	h, st := newTestServerWith(t, sender)
	const phone = "+79002223344"
	bindPhone(t, st, phone)

	doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": phone}, "")
	otp := sender.code(phone)

	if code, _ := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": "000000"}, ""); code != http.StatusUnauthorized {
		t.Fatalf("неверный код: ожидался 401, получен %d", code)
	}
	if code, m := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": otp}, ""); code != http.StatusOK {
		t.Fatalf("верный код после ошибки: %d %v", code, m)
	}
	// Код одноразовый.
	if code, _ := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": otp}, ""); code != http.StatusNotFound {
		t.Fatalf("повторное использование кода: ожидался 404, получен %d", code)
	}
}

func TestOTPRequiresTelegramBinding(t *testing.T) {
	sender := &fakeOTPSender{}
	h, _ := newTestServerWith(t, sender)
	// Номер не привязан к Telegram — понятная ошибка.
	if code, m := doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": "+79003334455"}, ""); code != http.StatusNotFound {
		t.Fatalf("непривязанный номер: ожидался 404, получен %d %v", code, m)
	}
}

func TestOTPUnavailableWithoutSender(t *testing.T) {
	h := newTestServer(t) // без сендера
	if code, _ := doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": "+79004445566"}, ""); code != http.StatusServiceUnavailable {
		t.Fatalf("без сендера: ожидался 503, получен %d", code)
	}
}

func TestOTPUsernameConflict(t *testing.T) {
	sender := &fakeOTPSender{}
	h, st := newTestServerWith(t, sender)
	bindPhone(t, st, "+79005556677")
	bindPhone(t, st, "+79006667788")

	// Первый регистрируется как @dad.
	for _, phone := range []string{"+79005556677", "+79006667788"} {
		doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": phone}, "")
		code, m := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": phone, "code": sender.code(phone)}, "")
		if code != http.StatusOK {
			t.Fatalf("verify %s: %d %v", phone, code, m)
		}
		token := m["token"].(string)
		username := "dad"
		if phone == "+79006667788" {
			username = "mom"
		}
		if c, _ := doReq(t, h, http.MethodPost, "/v1/account/profile", map[string]any{"name": "X", "username": username}, token); c != http.StatusOK {
			t.Fatalf("profile %s: %d", username, c)
		}
	}
	// Второй пытается занять @dad → 409.
	doReq(t, h, http.MethodPost, "/v1/auth/request_code", map[string]any{"phone": "+79006667788"}, "")
	code, m := doReq(t, h, http.MethodPost, "/v1/auth/verify_code", map[string]any{"phone": "+79006667788", "code": sender.code("+79006667788")}, "")
	token := m["token"].(string)
	if c, _ := doReq(t, h, http.MethodPost, "/v1/account/profile", map[string]any{"name": "X", "username": "dad"}, token); c != http.StatusConflict {
		t.Fatalf("занятый username: ожидался 409, получен %d (verify=%d)", c, code)
	}
}
