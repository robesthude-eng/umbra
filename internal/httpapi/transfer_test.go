package httpapi

import (
	"encoding/base64"
	"net/http"
	"strings"
	"testing"
)

// helper: создаёт пользователя, проходит challenge/verify и возвращает токен.
func registerAndAuth(t *testing.T, h http.Handler, username string) string {
	t.Helper()
	kb := newKeyBundle(username)
	if code, m := register(t, h, kb); code != http.StatusCreated {
		t.Fatalf("register %s: ожидался 201, получен %d (%v)", username, code, m)
	}
	return authenticate(t, h, username, kb.privEd)
}

func vaultB64(v string) string { return base64.StdEncoding.EncodeToString([]byte(v)) }

func TestAccountTransferRoundtrip(t *testing.T) {
	h := newTestServer(t)
	token := registerAndAuth(t, h, "alice")
	secret := "encrypted-vault-bytes"

	code, m := doReq(t, h, http.MethodPost, "/v1/account/transfer",
		map[string]any{"vault": vaultB64(secret)}, token)
	if code != http.StatusOK {
		t.Fatalf("create transfer: ожидался 200, получен %d (%v)", code, m)
	}
	transferCode, _ := m["code"].(string)
	if transferCode == "" {
		t.Fatalf("пустой код в ответе: %v", m)
	}

	// Claim без авторизации: код сам по себе — мандат.
	code, m = doReq(t, h, http.MethodPost, "/v1/account/transfer/claim",
		map[string]any{"code": transferCode}, "")
	if code != http.StatusOK {
		t.Fatalf("claim: ожидался 200, получен %d (%v)", code, m)
	}
	got, _ := m["vault"].(string)
	if got != vaultB64(secret) {
		t.Fatalf("vault не совпал: %q != %q", got, vaultB64(secret))
	}

	// Код одноразовый.
	code, _ = doReq(t, h, http.MethodPost, "/v1/account/transfer/claim",
		map[string]any{"code": transferCode}, "")
	if code != http.StatusNotFound {
		t.Fatalf("повторный claim должен вернуть 404, получен %d", code)
	}
}

func TestAccountTransferCreateRequiresAuth(t *testing.T) {
	h := newTestServer(t)
	code, _ := doReq(t, h, http.MethodPost, "/v1/account/transfer",
		map[string]any{"vault": vaultB64("x")}, "")
	if code != http.StatusUnauthorized {
		t.Fatalf("create transfer без токена: ожидался 401, получен %d", code)
	}
}

func TestAccountTransferNewCodeRevokesPrevious(t *testing.T) {
	h := newTestServer(t)
	token := registerAndAuth(t, h, "bob")

	_, m1 := doReq(t, h, http.MethodPost, "/v1/account/transfer",
		map[string]any{"vault": vaultB64("first")}, token)
	code1, _ := m1["code"].(string)
	if code1 == "" {
		t.Fatalf("пустой первый код: %v", m1)
	}
	_, m2 := doReq(t, h, http.MethodPost, "/v1/account/transfer",
		map[string]any{"vault": vaultB64("second")}, token)
	code2, _ := m2["code"].(string)

	// Первый код отозван появлением второго.
	if c, _ := doReq(t, h, http.MethodPost, "/v1/account/transfer/claim",
		map[string]any{"code": code1}, ""); c != http.StatusNotFound {
		t.Fatalf("старый код должен быть отозван, получен %d", c)
	}
	if c, m := doReq(t, h, http.MethodPost, "/v1/account/transfer/claim",
		map[string]any{"code": code2}, ""); c != http.StatusOK {
		t.Fatalf("новый код должен работать, получен %d (%v)", c, m)
	}
}

func TestAccountTransferClaimErrors(t *testing.T) {
	h := newTestServer(t)
	token := registerAndAuth(t, h, "carol")
	if code, _ := doReq(t, h, http.MethodPost, "/v1/account/transfer",
		map[string]any{"vault": vaultB64("v")}, token); code != http.StatusOK {
		t.Fatalf("create: %d", code)
	}

	// Несуществующий, но валидный по формату код.
	code, _ := doReq(t, h, http.MethodPost, "/v1/account/transfer/claim",
		map[string]any{"code": "ABCD-EFGH-JKMN"}, "")
	if code != http.StatusNotFound {
		t.Fatalf("чужой код: ожидался 404, получен %d", code)
	}
	// Мусорный код.
	code, _ = doReq(t, h, http.MethodPost, "/v1/account/transfer/claim",
		map[string]any{"code": "!!!"}, "")
	if code != http.StatusBadRequest {
		t.Fatalf("мусорный код: ожидался 400, получен %d", code)
	}
	// Код в нижнем регистре/с разделителями нормализуется.
	_, m := doReq(t, h, http.MethodPost, "/v1/account/transfer",
		map[string]any{"vault": vaultB64("norm")}, token)
	raw, _ := m["code"].(string)
	claimCode := strings.ToLower(strings.ReplaceAll(raw, "-", ""))
	if c, _ := doReq(t, h, http.MethodPost, "/v1/account/transfer/claim",
		map[string]any{"code": claimCode}, ""); c != http.StatusOK {
		t.Fatalf("код в нижнем регистре должен нормализоваться, получен %d", c)
	}
}
