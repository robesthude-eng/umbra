package httpapi

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestTurnCredentialsMatchCoturnScheme(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	username, credential := turnCredentials("s3cret", "user-1", time.Hour, now)

	expiry, owner, ok := strings.Cut(username, ":")
	if !ok || owner != "user-1" {
		t.Fatalf("unexpected username %q", username)
	}
	seconds, err := strconv.ParseInt(expiry, 10, 64)
	if err != nil {
		t.Fatalf("expiry is not a number: %v", err)
	}
	if want := now.Add(time.Hour).Unix(); seconds != want {
		t.Fatalf("expiry = %d, want %d", seconds, want)
	}

	mac := hmac.New(sha1.New, []byte("s3cret"))
	mac.Write([]byte(username))
	if want := base64.StdEncoding.EncodeToString(mac.Sum(nil)); credential != want {
		t.Fatalf("credential = %q, want %q", credential, want)
	}
}

func TestTurnCredentialsDifferPerUser(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	_, first := turnCredentials("s3cret", "user-1", time.Hour, now)
	_, second := turnCredentials("s3cret", "user-2", time.Hour, now)
	if first == second {
		t.Fatal("credentials must depend on the user id")
	}
}

func TestSplitIceURLs(t *testing.T) {
	got := splitIceURLs(" turn:example.com:3478?transport=udp , turns:example.com:5349 ")
	if len(got) != 2 || got[0] != "turn:example.com:3478?transport=udp" || got[1] != "turns:example.com:5349" {
		t.Fatalf("unexpected urls %#v", got)
	}
	if len(splitIceURLs("   ")) != 0 {
		t.Fatal("blank value must produce no urls")
	}
}
