package httpapi

import (
	"context"
	"crypto/ed25519"
	"errors"
	"net/http"
	"testing"
	"time"

	"umbra/server/internal/config"
	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

func TestDefaultServerRequiresOTP(t *testing.T) {
	st := store.NewMemoryStore()
	hub := ws.NewHub()
	t.Cleanup(hub.Close)
	sender := &fakeOTPSender{}
	h := NewServerForMain(&config.Config{TokenTTL: time.Hour, MaxMessageBytes: 1 << 20, TelegramChatID: 42}, st, hub, nil, sender).Handler
	for _, path := range []string{"/v1/register", "/v1/auth/challenge", "/v1/auth/verify"} {
		if code, body := doReq(t, h, http.MethodPost, path, newKeyBundle("outsider").reg, ""); code != http.StatusGone {
			t.Fatalf("%s: %d %v", path, code, body)
		}
	}
	account, token := registerVerifiedPhone(t, h, sender, "Владелец", "+79991234567")
	if account["id"] == "" || token == "" {
		t.Fatal("OTP did not create a usable account")
	}
}

func TestLegacyRegistrationCannotClaimPhone(t *testing.T) {
	h, st := newTestServerWith(t, nil) // explicit legacy compatibility mode
	key := newKeyBundle("pretender")
	key.reg["phone"], key.reg["name"] = "+79991234567", "Претендент"
	if code, body := register(t, h, key); code != http.StatusForbidden {
		t.Fatalf("unverified phone registration: %d %v", code, body)
	}
	if _, err := st.GetUserByPhone(context.Background(), "+79991234567"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("unverified phone was reserved: %v", err)
	}
}

func TestExistingPhoneKeysCannotAuthenticateAfterOTP(t *testing.T) {
	sender := &fakeOTPSender{}
	h, st := newTestServerWith(t, sender)
	key := newKeyBundle("old_phone_account")
	// Reproduce an account created by the vulnerable server before the upgrade.
	u := &model.User{ID: "old-account", Username: "old_phone_account", Phone: "+79991234567",
		PhoneHash: PhoneHash("+79991234567"), IdentityEd25519: key.privEd.Public().(ed25519.PublicKey), CreatedAt: time.Now()}
	if err := st.CreateUser(context.Background(), u); err != nil {
		t.Fatal(err)
	}
	account, _ := registerVerifiedPhone(t, h, sender, "Владелец", u.Phone)
	if account["id"] != u.ID {
		t.Fatal("OTP lost the existing account")
	}
	for _, login := range []map[string]any{{"phone": u.Phone}, {"username": u.Username}} {
		if code, _ := doReq(t, h, http.MethodPost, "/v1/auth/challenge", login, ""); code != http.StatusNotFound {
			t.Fatalf("phone account accepted key challenge: %d", code)
		}
	}
	// Test verify with a valid stored challenge, not just a missing nonce.
	s := &Server{cfg: &config.Config{AllowLegacyAuth: true, TokenTTL: time.Hour}, store: st, challenges: newChallengeStore()}
	challenge := "valid-old-challenge"
	s.challenges.put(challenge, time.Minute, u.Username)
	for _, login := range []map[string]any{{"phone": u.Phone}, {"username": u.Username}} {
		login["challenge"] = challenge
		login["signature"] = b64e(ed25519.Sign(key.privEd, []byte(challenge)))
		if code, _ := doReq(t, http.HandlerFunc(s.handleAuthVerify), http.MethodPost, "/v1/auth/verify", login, ""); code != http.StatusUnauthorized {
			t.Fatalf("old phone key authenticated: %d", code)
		}
	}
}

func TestSessionRefreshKeepsCredentialAndRejectsRevokedToken(t *testing.T) {
	h, st := newTestServerWith(t, nil)
	key := newKeyBundle("alice")
	if code, _ := register(t, h, key); code != http.StatusCreated {
		t.Fatal(code)
	}
	token := authenticate(t, h, "alice", key.privEd)
	userID, err := st.GetUserIDByTokenHash(context.Background(), crypto.HashToken(token))
	if err != nil {
		t.Fatal(err)
	}
	// Shorten the original token to demonstrate that the endpoint extends it.
	if err := st.PutToken(context.Background(), crypto.HashToken(token), userID, time.Now().Add(time.Minute)); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2; i++ {
		code, body := doReq(t, h, http.MethodPost, "/v1/auth/refresh", nil, token)
		if code != http.StatusOK {
			t.Fatalf("refresh: %d %v", code, body)
		}
		expires, err := time.Parse(time.RFC3339Nano, body["expires_at"].(string))
		if err != nil || time.Until(expires) < 50*time.Minute {
			t.Fatalf("session not extended: %v %v", expires, err)
		}
		if _, changed := body["token"]; changed {
			t.Fatal("refresh must not replace the credential")
		}
	}
	if code, _ := doReq(t, h, http.MethodGet, "/v1/account", nil, token); code != http.StatusOK {
		t.Fatal("credential stopped working")
	}
	if code, _ := doReq(t, h, http.MethodPost, "/v1/auth/logout", nil, token); code != http.StatusOK {
		t.Fatal(code)
	}
	if code, _ := doReq(t, h, http.MethodPost, "/v1/auth/refresh", nil, token); code != http.StatusUnauthorized {
		t.Fatal("logout token renewed")
	}
	if err := st.PutToken(context.Background(), crypto.HashToken("expired"), userID, time.Now().Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	if code, _ := doReq(t, h, http.MethodPost, "/v1/auth/refresh", nil, "expired"); code != http.StatusUnauthorized {
		t.Fatal("expired token renewed")
	}
}
