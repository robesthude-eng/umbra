package httpapi

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"umbra/server/internal/config"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

func TestLegacyEndpointsAndWebSocket(t *testing.T) {
	st := store.NewMemoryStore()
	hub := ws.NewHub()
	go hub.Run()
	cfg := &config.Config{AllowLegacyAuth: true, TokenTTL: time.Hour, MaxMessageBytes: 1 << 20}
	srv := httptest.NewServer(NewServer(cfg, st, hub).Handler)
	t.Cleanup(srv.Close)
	client := &http.Client{Timeout: 5 * time.Second}
	call := func(method, path, token string, payload any, status int, out any) {
		t.Helper()
		body, err := json.Marshal(payload)
		if err != nil {
			t.Fatal(err)
		}
		req, err := http.NewRequest(method, srv.URL+path, bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		if resp.StatusCode != status {
			t.Fatalf("%s %s: %d, want %d", method, path, resp.StatusCode, status)
		}
		if out != nil {
			if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
				t.Fatal(err)
			}
		}
	}
	call("GET", "/healthz", "", nil, 200, nil)
	ids, tokens := make(map[string]string), make(map[string]string)
	for _, name := range []string{"alice", "bob"} {
		pub, priv, err := ed25519.GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		ix, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		spk, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		otk, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		registration := registerRequest{
			Username: name, IdentityEd25519: b64e(pub), IdentityX25519: b64e(ix.PublicKey().Bytes()),
			SignedPrekey: b64e(spk.PublicKey().Bytes()), SignedPrekeySig: b64e(ed25519.Sign(priv, spk.PublicKey().Bytes())),
			OneTimePrekeys: []string{b64e(otk.PublicKey().Bytes())},
		}
		var registered map[string]string
		call("POST", "/v1/register", "", registration, 201, &registered)
		ids[name] = registered["id"]
		call("POST", "/v1/register", "", registration, 409, nil)
		var ch challengeResponse
		call("POST", "/v1/auth/challenge", "", challengeRequest{Username: name}, 200, &ch)
		verify := verifyRequest{Username: name, Challenge: ch.Challenge, Signature: b64e(ed25519.Sign(priv, []byte(ch.Challenge)))}
		var auth verifyResponse
		call("POST", "/v1/auth/verify", "", verify, 200, &auth)
		if auth.Token == "" {
			t.Fatal("empty token")
		}
		tokens[name] = auth.Token
		call("POST", "/v1/auth/verify", "", verify, 401, nil)
	}
	var bundle prekeysResponse
	call("GET", "/v1/users/bob/prekeys", "", nil, 200, &bundle)
	if bundle.ID != ids["bob"] || bundle.OneTimePrekey == "" {
		t.Fatalf("prekeys: %#v", bundle)
	}
	call("GET", "/v1/users/bob/prekeys", "", nil, 200, &bundle)
	if bundle.OneTimePrekey != "" {
		t.Fatal("one-time prekey reused")
	}
	call("GET", "/v1/messages", "", nil, 401, nil)
	call("GET", "/v1/ws", "", nil, 401, nil)
	conn, resp, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL, "http")+"/v1/ws?token="+tokens["bob"], nil)
	if err != nil {
		if resp != nil {
			_ = resp.Body.Close()
		}
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	deadline := time.Now().Add(5 * time.Second)
	for !hub.Online(ids["bob"]) {
		if time.Now().After(deadline) {
			t.Fatal("WebSocket registration timed out")
		}
		time.Sleep(time.Millisecond)
	}
	ct := b64e([]byte{0, 255, 1, 128, 42})
	var sent messageResponse
	call("POST", "/v1/messages", tokens["alice"], sendMessageRequest{RecipientID: ids["bob"], Ciphertext: ct}, 201, &sent)
	var event struct {
		Type string          `json:"type"`
		Data messageResponse `json:"data"`
	}
	if err := conn.SetReadDeadline(time.Now().Add(5 * time.Second)); err != nil {
		t.Fatal(err)
	}
	if err := conn.ReadJSON(&event); err != nil {
		t.Fatal(err)
	}
	if event.Type != "message" || event.Data.ID != sent.ID || event.Data.Ciphertext != ct {
		t.Fatalf("WebSocket delivery changed: %#v", event)
	}
	var inbox struct {
		Messages []messageResponse `json:"messages"`
	}
	call("GET", "/v1/messages", tokens["bob"], nil, 200, &inbox)
	if len(inbox.Messages) != 1 || inbox.Messages[0].ID != sent.ID || inbox.Messages[0].Ciphertext != ct {
		t.Fatalf("inbox changed: %#v", inbox)
	}
	// Облачная история: alice тоже видит собственное отправленное (своя сторона DM).
	call("GET", "/v1/messages", tokens["alice"], nil, 200, &inbox)
	if len(inbox.Messages) != 1 || inbox.Messages[0].ID != sent.ID || inbox.Messages[0].Ciphertext != ct {
		t.Fatalf("alice не видит свою отправленную историю: %#v", inbox)
	}
}
