package push

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"strings"
	"testing"
	"time"
)

// testCredentials готовит JSON сервисного аккаунта с настоящим RSA-ключом.
func testCredentials(t *testing.T) []byte {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatalf("marshal key: %v", err)
	}
	pemKey := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
	payload, err := json.Marshal(map[string]string{
		"type":         "service_account",
		"project_id":   "umbra-test",
		"client_email": "push@umbra-test.iam.gserviceaccount.com",
		"private_key":  string(pemKey),
	})
	if err != nil {
		t.Fatalf("marshal credentials: %v", err)
	}
	return payload
}

func TestNewFCMReadsServiceAccount(t *testing.T) {
	f, err := NewFCM(testCredentials(t), "")
	if err != nil {
		t.Fatalf("NewFCM: %v", err)
	}
	if f.ProjectID() != "umbra-test" {
		t.Fatalf("project id = %q, want umbra-test", f.ProjectID())
	}
	if !strings.HasSuffix(f.endpoint, "/v1/projects/umbra-test/messages:send") {
		t.Fatalf("endpoint = %q", f.endpoint)
	}
}

func TestNewFCMRejectsIncompleteCredentials(t *testing.T) {
	if _, err := NewFCM([]byte(`{"project_id":"umbra-test"}`), ""); err == nil {
		t.Fatal("expected an error for credentials without client_email")
	}
	if _, err := NewFCM([]byte("not json"), ""); err == nil {
		t.Fatal("expected an error for broken json")
	}
}

func TestAssertionCarriesScopeAndIssuer(t *testing.T) {
	f, err := NewFCM(testCredentials(t), "")
	if err != nil {
		t.Fatalf("NewFCM: %v", err)
	}
	now := time.Unix(1_700_000_000, 0)
	assertion, err := f.assertion(now)
	if err != nil {
		t.Fatalf("assertion: %v", err)
	}
	parts := strings.Split(assertion, ".")
	if len(parts) != 3 {
		t.Fatalf("assertion has %d parts, want 3", len(parts))
	}
	claims, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatalf("decode claims: %v", err)
	}
	var parsed struct {
		Iss   string `json:"iss"`
		Scope string `json:"scope"`
		Aud   string `json:"aud"`
		Exp   int64  `json:"exp"`
	}
	if err := json.Unmarshal(claims, &parsed); err != nil {
		t.Fatalf("claims: %v", err)
	}
	if parsed.Scope != scope {
		t.Fatalf("scope = %q", parsed.Scope)
	}
	if parsed.Iss != "push@umbra-test.iam.gserviceaccount.com" {
		t.Fatalf("iss = %q", parsed.Iss)
	}
	if parsed.Aud != defaultTokenURI {
		t.Fatalf("aud = %q", parsed.Aud)
	}
	if parsed.Exp != now.Add(time.Hour).Unix() {
		t.Fatalf("exp = %d", parsed.Exp)
	}
}
