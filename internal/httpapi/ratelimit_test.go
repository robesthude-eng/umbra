package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"umbra/server/internal/config"
)

func TestProxyClientAddress(t *testing.T) {
	trusted, err := config.ParseTrustedProxies("127.0.0.1,::1,10.1.0.0/16")
	if err != nil {
		t.Fatal(err)
	}
	l := newRequestLimiter(trusted...)
	for _, tc := range []struct{ name, peer, forwarded, want string }{
		{"direct spoof ignored", "198.51.100.10:1234", "203.0.113.7", "198.51.100.10"},
		{"trusted proxy", "127.0.0.1:1234", "203.0.113.7", "203.0.113.7"},
		{"spoofed left hop ignored", "127.0.0.1:1234", "192.0.2.99, 203.0.113.7", "203.0.113.7"},
		{"trusted chain", "127.0.0.1:1234", "203.0.113.7, 10.1.0.2", "203.0.113.7"},
		{"invalid nearest hop", "127.0.0.1:1234", "203.0.113.7, invalid", "127.0.0.1"},
		{"no forwarded header", "127.0.0.1:1234", "", "127.0.0.1"},
		{"IPv6", "[::1]:1234", "2001:db8::1", "2001:db8::1"},
		{"mapped IPv4", "[::ffff:127.0.0.1]:1234", "::ffff:203.0.113.7", "203.0.113.7"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPost, "/v1/auth/request_code", nil)
			r.RemoteAddr = tc.peer
			r.Header.Set("X-Forwarded-For", tc.forwarded)
			if got := l.clientIP(r); got != tc.want {
				t.Fatalf("got %q, want %q", got, tc.want)
			}
		})
	}
}

func TestProxyUsersHaveSeparateOTPLimits(t *testing.T) {
	trusted, _ := config.ParseTrustedProxies("127.0.0.1")
	h := newRequestLimiter(trusted...).wrap(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusNoContent) }))
	request := func(peer, forwarded string) int {
		r := httptest.NewRequest(http.MethodPost, "/v1/auth/request_code", nil)
		r.RemoteAddr = peer
		r.Header.Set("X-Forwarded-For", forwarded)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w.Code
	}
	for i := 0; i < 6; i++ {
		if code := request("127.0.0.1:1234", "203.0.113.1"); code != 204 {
			t.Fatal(code)
		}
	}
	if code := request("127.0.0.1:1234", "203.0.113.1"); code != 429 {
		t.Fatal("limit not enforced")
	}
	if code := request("127.0.0.1:1234", "203.0.113.2"); code != 204 {
		t.Fatal("users share proxy limit")
	}
	for i := 0; i < 6; i++ {
		if code := request("198.51.100.1:1234", "203.0.113.1"); code != 204 {
			t.Fatal(code)
		}
	}
	if code := request("198.51.100.1:1234", "203.0.113.2"); code != 429 {
		t.Fatal("spoofed header bypassed direct limit")
	}
}
