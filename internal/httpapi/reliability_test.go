package httpapi

import (
	"bytes"
	"crypto/ed25519"
	"crypto/x509"
	"encoding/base64"
	"net/http"
	"testing"
	"time"
)

func TestRegisterLegacyDERAndLogout(t *testing.T) {
	h := newTestServer(t)
	kb := newKeyBundle("legacy")
	der, err := x509.MarshalPKIXPublicKey(kb.privEd.Public())
	if err != nil {
		t.Fatal(err)
	}
	kb.reg["identity_ed25519"] = b64e(der)
	if code, body := register(t, h, kb); code != 201 {
		t.Fatalf("register DER: %d %v", code, body)
	}
	token := authenticate(t, h, "legacy", kb.privEd)
	if code, _ := doReq(t, h, "POST", "/v1/auth/logout", nil, token); code != 200 {
		t.Fatalf("logout: %d", code)
	}
	if code, _ := doReq(t, h, "GET", "/v1/account", nil, token); code != 401 {
		t.Fatalf("revoked token accepted: %d", code)
	}
	// Logout не удаляет аккаунт или identity: тот же ключ по-прежнему позволяет войти.
	if authenticate(t, h, "legacy", kb.privEd) == "" {
		t.Fatal("keys lost after logout")
	}
}

func TestChallengeBoundToUsernameAndBounded(t *testing.T) {
	c := newChallengeStore()
	if !c.put("nonce", time.Minute, "alice") {
		t.Fatal("put failed")
	}
	if c.consume("nonce", "bob") {
		t.Fatal("nonce accepted for another account")
	}
	if !c.consume("nonce", "alice") || c.consume("nonce", "alice") {
		t.Fatal("nonce not single-use")
	}
	c.put("expired", -time.Second, "alice")
	if c.consume("expired", "alice") {
		t.Fatal("expired nonce accepted")
	}
	for i := 0; i < 4096; i++ {
		c.put(string(rune(i)), time.Minute, "alice")
	}
	if c.put("overflow", time.Minute, "alice") {
		t.Fatal("unbounded challenge store")
	}
}

func TestV2KeyTransportAndAtomicPublication(t *testing.T) {
	h := newTestServer(t)
	kb := newKeyBundle("signal_user")
	if code, _ := register(t, h, kb); code != 201 {
		t.Fatalf("legacy registration: %d", code)
	}
	token := authenticate(t, h, "signal_user", kb.privEd)
	// Тестируем транспорт v2. Реальную Curve-подпись проверяет libsignal в Android-тестах.
	for _, field := range []string{"identity_x25519", "signed_prekey"} {
		raw, err := base64.StdEncoding.DecodeString(kb.reg[field].(string))
		if err != nil {
			t.Fatal(err)
		}
		kb.reg[field] = b64e(append([]byte{5}, raw...))
	}
	kb.reg["signed_prekey_signature"] = b64e(bytes.Repeat([]byte{7}, 64))
	kb.reg["key_version"], kb.reg["registration_id"], kb.reg["signed_prekey_id"] = 2, 42, 73
	kb.reg["one_time_prekeys"] = []string{kb.reg["signed_prekey"].(string)}
	kb.reg["one_time_prekey_ids"], kb.reg["key_bundle_id"] = []int{99}, "batch-1"
	if code, body := doReq(t, h, "PUT", "/v1/account/keys", kb.reg, token); code != 200 {
		t.Fatalf("upgrade keys: %d %v", code, body)
	}
	code, bundle := doReq(t, h, "GET", "/v1/users/signal_user/prekeys", nil, "")
	if code != 200 || bundle["key_version"] != float64(2) || bundle["one_time_prekey_id"] != float64(99) || bundle["registration_id"] != float64(42) {
		t.Fatalf("bundle: %d %v", code, bundle)
	}
	if code, _ := doReq(t, h, "PUT", "/v1/account/keys", kb.reg, token); code != 200 {
		t.Fatalf("retry publish: %d", code)
	}
	_, exhausted := doReq(t, h, "GET", "/v1/users/signal_user/prekeys", nil, "")
	if exhausted["one_time_prekey"] != "" || exhausted["one_time_prekey_id"] != float64(0) {
		t.Fatalf("prekey republished: %v", exhausted)
	}
	kb.reg["key_bundle_id"] = "batch-2"
	kb.reg["identity_x25519"] = b64e(append([]byte{5}, bytes.Repeat([]byte{8}, 32)...))
	if code, _ := doReq(t, h, "PUT", "/v1/account/keys", kb.reg, token); code != 409 {
		t.Fatalf("identity rotation accepted: %d", code)
	}
}

func TestSendRetryAndValidation(t *testing.T) {
	h := newTestServer(t)
	alice, bob := newKeyBundle("alice"), newKeyBundle("bob")
	if code, _ := register(t, h, alice); code != 201 {
		t.Fatal(code)
	}
	code, registered := register(t, h, bob)
	if code != 201 {
		t.Fatal(code)
	}
	a, b := authenticate(t, h, "alice", alice.privEd), authenticate(t, h, "bob", bob.privEd)
	req := map[string]any{"recipient_id": registered["id"], "ciphertext": b64e([]byte("opaque")), "client_message_id": "client-1", "expires_in": 60}
	code, first := doReq(t, h, "POST", "/v1/messages", req, a)
	if code != 201 {
		t.Fatalf("send: %d %v", code, first)
	}
	code, retry := doReq(t, h, "POST", "/v1/messages", req, a)
	if code != 201 || retry["id"] != first["id"] || retry["created_at"] != first["created_at"] || retry["expires_at"] != first["expires_at"] {
		t.Fatalf("retry differs: %v %v", first, retry)
	}
	_, inbox := doReq(t, h, "GET", "/v1/messages", nil, b)
	if len(inbox["messages"].([]any)) != 1 {
		t.Fatal("duplicate message persisted")
	}
	if got := inbox["messages"].([]any)[0].(map[string]any)["client_message_id"]; got != "client-1" {
		t.Fatalf("REST history must retain the acknowledgement id: %v", got)
	}
	req["ciphertext"] = b64e([]byte("different"))
	if code, _ := doReq(t, h, "POST", "/v1/messages", req, a); code != 409 {
		t.Fatal("client id conflict not rejected")
	}
	for _, ttl := range []int64{-1, 2_592_001, 1 << 62} {
		req["expires_in"] = ttl
		if code, _ := doReq(t, h, "POST", "/v1/messages", req, a); code != http.StatusBadRequest {
			t.Fatalf("invalid TTL %d: %d", ttl, code)
		}
	}
	for _, query := range []string{"?since=invalid", "?limit=0", "?limit=501"} {
		if code, _ := doReq(t, h, "GET", "/v1/messages"+query, nil, b); code != 400 {
			t.Fatalf("invalid cursor query: %d", code)
		}
	}
}

func TestChallengeCannotAuthenticateAnotherAccount(t *testing.T) {
	h := newTestServer(t)
	alice, bob := newKeyBundle("alice"), newKeyBundle("bob")
	register(t, h, alice)
	register(t, h, bob)
	_, challenge := doReq(t, h, "POST", "/v1/auth/challenge", map[string]string{"username": "alice"}, "")
	ch := challenge["challenge"].(string)
	body := map[string]string{"username": "bob", "challenge": ch, "signature": b64e(ed25519.Sign(bob.privEd, []byte(ch)))}
	if code, _ := doReq(t, h, "POST", "/v1/auth/verify", body, ""); code != 401 {
		t.Fatalf("cross-account challenge accepted: %d", code)
	}
}
