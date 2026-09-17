package httpapi

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
	"umbra/server/internal/config"
	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

func TestOTPBudgetBindingAndSingleUse(t *testing.T) {
	o := newOTPStore(store.NewMemoryStore())
	now := time.Now()
	o.now = func() time.Time { return now }
	issue := func(purpose, binding string) string {
		t.Helper()
		id, _, err := o.issue(context.Background(), "phone", purpose, binding, "Device", "123456", false)
		if err != nil {
			t.Fatal(err)
		}
		return id
	}
	id := issue("login", "")
	if _, retry, err := o.issue(context.Background(), "phone", "login", "", "", "123456", false); !errors.Is(err, otpErrTooMany) || retry <= 0 {
		t.Fatal("cooldown", err, retry)
	}
	if _, err := o.verify(context.Background(), "phone", "login", "", "", "123456"); !errors.Is(err, otpErrExpired) {
		t.Fatal("missing ticket accepted", err)
	}
	if _, err := o.verify(context.Background(), "phone", "delete_account", "", id, "123456"); !errors.Is(err, otpErrExpired) {
		t.Fatal("purpose mismatch", err)
	}
	for round := 0; round < 2; round++ {
		for i := 0; i < 5; i++ {
			if _, err := o.verify(context.Background(), "phone", "login", "", id, "999999"); !errors.Is(err, otpErrInvalid) {
				t.Fatal(err)
			}
		}
		if _, err := o.verify(context.Background(), "phone", "login", "", id, "123456"); !errors.Is(err, otpErrTooMany) {
			t.Fatal("exhausted code", err)
		}
		now = now.Add(otpCooldown)
		if round == 0 {
			id = issue("login", "")
		}
	}
	if _, _, err := o.issue(context.Background(), "phone", "login", "", "", "123456", false); !errors.Is(err, otpErrTooMany) {
		t.Fatal("resend reset failures", err)
	}
	now = now.Add(otpWindow)
	id = issue("delete_account", "session-a")
	if _, err := o.verify(context.Background(), "phone", "delete_account", "session-b", id, "123456"); !errors.Is(err, otpErrExpired) {
		t.Fatal("session mismatch", err)
	}
	var successes atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := o.verify(context.Background(), "phone", "delete_account", "session-a", id, "123456"); err == nil {
				successes.Add(1)
			}
		}()
	}
	wg.Wait()
	if successes.Load() != 1 {
		t.Fatal("consumed", successes.Load())
	}
	now = now.Add(otpCooldown)
	id = issue("login", "")
	now = now.Add(otpTTL)
	if _, err := o.verify(context.Background(), "phone", "login", "", id, "123456"); !errors.Is(err, otpErrExpired) {
		t.Fatal("expiry", err)
	}
}
func TestOTPSendBudgetAndCapacity(t *testing.T) {
	o := newOTPStore(store.NewMemoryStore())
	now := time.Now()
	o.now = func() time.Time { return now }
	for i := 0; i < otpMaxSends; i++ {
		id, _, err := o.issue(context.Background(), "phone", "login", "", "", "123456", false)
		if err != nil {
			t.Fatal(err)
		}
		if _, err = o.verify(context.Background(), "phone", "login", "", id, "123456"); err != nil {
			t.Fatal(err)
		}
		now = now.Add(otpCooldown)
	}
	if _, _, err := o.issue(context.Background(), "phone", "login", "", "", "123456", false); !errors.Is(err, otpErrTooMany) {
		t.Fatal("successful use reset sends", err)
	}
	for i := 1; i < otpMaxPhones; i++ {
		if _, _, err := o.issue(context.Background(), fmt.Sprint(i), "login", "", "", "123456", false); err != nil {
			t.Fatal(err)
		}
	}
	if _, _, err := o.issue(context.Background(), "overflow", "login", "", "", "123456", false); !errors.Is(err, otpErrCapacity) {
		t.Fatal("unbounded", err)
	}
	now = now.Add(otpWindow + otpTTL)
	if _, _, err := o.issue(context.Background(), "overflow", "login", "", "", "123456", false); err != nil {
		t.Fatal("capacity not reclaimed", err)
	}
}
func TestOTPLateWindowCodeSurvivesCleanup(t *testing.T) {
	o := newOTPStore(store.NewMemoryStore())
	now := time.Now()
	o.now = func() time.Time { return now }
	_, _, _ = o.issue(context.Background(), "phone", "login", "", "", "000000", false)
	now = now.Add(otpWindow - time.Minute)
	id, _, err := o.issue(context.Background(), "phone", "login", "", "", "123456", false)
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(2 * time.Minute)
	if _, err = o.verify(context.Background(), "phone", "login", "", id, "123456"); err != nil {
		t.Fatal("valid code lost at budget boundary", err)
	}
}
func newPolicyServer(t *testing.T, allowed string, sender OTPSender) (*Server, *store.MemoryStore) {
	t.Helper()
	st := store.NewMemoryStore()
	hub := ws.NewHub()
	t.Cleanup(hub.Close)
	h := NewServerForMain(&config.Config{AllowedPhones: allowed, TokenTTL: time.Hour, MaxMessageBytes: 1 << 20, TelegramChatID: 42}, st, hub, nil, sender).Handler
	return h.(*Server), st
}
func securityLogin(t *testing.T, h http.Handler, sender *fakeOTPSender, phone string) (string, string) {
	t.Helper()
	c, m := doReq(t, h, "POST", "/v1/auth/request_code", map[string]any{"phone": phone, "device_name": "Test\n\u202ePhone"}, "")
	if c != 200 {
		t.Fatal(c, m)
	}
	c, m = doReq(t, h, "POST", "/v1/auth/verify_code", map[string]any{"phone": phone, "code": sender.code(phone), "request_id": m["request_id"]}, "")
	if c != 200 {
		t.Fatal(c, m)
	}
	return m["token"].(string), m["account"].(map[string]any)["id"].(string)
}
func TestPhonePolicyAndSessionOwnership(t *testing.T) {
	const phone = "+79991234567"
	sender := &fakeOTPSender{}
	h, st := newPolicyServer(t, "", sender)
	if c, _ := doReq(t, h, "POST", "/v1/auth/request_code", map[string]any{"phone": phone}, ""); c != 403 {
		t.Fatal("empty policy allowed signup", c)
	}
	if err := st.CreateUser(context.Background(), &model.User{ID: "owner", Username: "owner", Phone: phone, CreatedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}
	token, uid := securityLogin(t, h, sender, phone)
	advanceOTPClock(h)
	other, _ := securityLogin(t, h, sender, phone)
	c, m := doReq(t, h, "GET", "/v1/account/sessions", nil, token)
	if c != 200 {
		t.Fatal(c, m)
	}
	sessions := m["sessions"].([]any)
	if len(sessions) != 2 {
		t.Fatal(m)
	}
	current := 0
	for _, raw := range sessions {
		s := raw.(map[string]any)
		if s["device_name"] != "TestPhone" {
			t.Fatal("unsanitized device", s)
		}
		for _, k := range []string{"token", "token_hash", "user_id"} {
			if _, ok := s[k]; ok {
				t.Fatal("credential leaked", k)
			}
		}
		if s["current"] == true {
			current++
		}
	}
	if current != 1 {
		t.Fatal("current", m)
	}
	if err := st.SavePushDeviceForSession(context.Background(), uid, "push", "android", crypto.HashToken(other)); err != nil {
		t.Fatal(err)
	}
	if c, _ := doReq(t, h, "DELETE", "/v1/account/sessions/foreign", nil, token); c != 404 {
		t.Fatal(c)
	}
	if c, _ := doReq(t, h, "POST", "/v1/account/sessions/revoke_others", nil, token); c != 200 {
		t.Fatal(c)
	}
	if c, _ := doReq(t, h, "POST", "/v1/auth/refresh", nil, other); c != 401 {
		t.Fatal("revoked token renewed", c)
	}
	if devices, _ := st.ListPushDevices(context.Background(), uid); len(devices) != 0 {
		t.Fatal("push survived revoke", devices)
	}
	if c, _ := doReq(t, h, "GET", "/v1/account", nil, token); c != 200 {
		t.Fatal("current revoked", c)
	}
	h.allowedPhones = map[string]bool{"+79990000000": true}
	if c, _ := doReq(t, h, "GET", "/v1/account", nil, token); c != 401 {
		t.Fatal("excluded phone accepted", c)
	}
}
func TestAccountDeletionRequiresSeparateBoundProof(t *testing.T) {
	const phone = "+79991234567"
	sender := &fakeOTPSender{}
	h, _ := newPolicyServer(t, phone, sender)
	token, _ := securityLogin(t, h, sender, phone)
	if c, _ := doReq(t, h, "POST", "/v1/account/burn", nil, token); c != 400 {
		t.Fatal(c)
	}
	if c, _ := doReq(t, h, "POST", "/v1/account/burn", map[string]any{"request_id": "login", "code": sender.code(phone)}, token); c != 404 {
		t.Fatal("login proof accepted", c)
	}
	advanceOTPClock(h)
	other, _ := securityLogin(t, h, sender, phone)
	advanceOTPClock(h)
	c, m := doReq(t, h, "POST", "/v1/account/security/code", map[string]any{"purpose": "delete_account"}, token)
	if c != 200 {
		t.Fatal(c, m)
	}
	proof := map[string]any{"request_id": m["request_id"], "code": sender.code(phone)}
	if c, _ := doReq(t, h, "POST", "/v1/account/burn", proof, other); c != 404 {
		t.Fatal("other session used proof", c)
	}
	if c, _ := doReq(t, h, "POST", "/v1/account/burn", map[string]any{"request_id": m["request_id"], "code": differentCode(sender.code(phone))}, token); c != 400 {
		t.Fatal("wrong proof must not log out", c)
	}
	if c, _ := doReq(t, h, "GET", "/v1/account", nil, token); c != 200 {
		t.Fatal(c)
	}
	if c, m := doReq(t, h, "POST", "/v1/account/burn", proof, token); c != 200 {
		t.Fatal(c, m)
	}
	for _, tok := range []string{token, other} {
		if c, _ := doReq(t, h, "GET", "/v1/account", nil, tok); c != 401 {
			t.Fatal("burn left session", c)
		}
	}
}

type failedSender struct{}

func (failedSender) SendCode(context.Context, string, int64, string) error {
	return errors.New("delivery failed")
}
func TestFailedOTPDeliveryInvalidatesCodeKeepsCooldown(t *testing.T) {
	const phone = "+79991234567"
	h, st := newPolicyServer(t, phone, failedSender{})
	if c, _ := doReq(t, h, "POST", "/v1/auth/request_code", map[string]any{"phone": phone}, ""); c != 502 {
		t.Fatal(c)
	}
	// Недоставленный код удалён из хранилища, но кулдаун остаётся (v0.19).
	if _, _, err := st.LoadOTPCode(context.Background(), phone, "login"); !errors.Is(err, store.ErrNotFound) {
		t.Fatal("undelivered code retained", err)
	}
	if c, _ := doReq(t, h, "POST", "/v1/auth/request_code", map[string]any{"phone": phone}, ""); c != 429 {
		t.Fatal("cooldown reset", c)
	}
}
