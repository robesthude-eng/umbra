package telegram

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

// Клиент бота обязан ходить по HTTP/1.1: Cloudflare Worker-релей периодически
// сбрасывает h2-стримы коротких getUpdates ("stream error: PROTOCOL_ERROR").
// Тест фиксирует отключённый апгрейд в HTTP/2.
func TestNewClientForcesHTTP1(t *testing.T) {
	c := NewClient("token", "", "key")

	tr, ok := c.http.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("transport = %T, want *http.Transport", c.http.Transport)
	}
	if tr.ForceAttemptHTTP2 {
		t.Error("ForceAttemptHTTP2 = true, want false")
	}
	if tr.TLSNextProto == nil {
		t.Error("TLSNextProto = nil, want empty map (h2 disabled)")
	}
	if len(tr.TLSNextProto) != 0 {
		t.Errorf("TLSNextProto has %d entries, want 0", len(tr.TLSNextProto))
	}
	if !tr.DisableKeepAlives {
		t.Error("DisableKeepAlives = false, want true: переиспользование " +
			"соединений к workers.dev периодически подвешивает запросы")
	}
}

// При заданном base (релей) токен в путь не подставляется, ключ идёт в
// x-umbra-key, а запрос должен приходить ровно по HTTP/1.1.
func TestPostUsesHTTP11(t *testing.T) {
	proto := ""
	path := ""
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		proto = r.Proto
		path = r.URL.Path
		if r.Header.Get("x-umbra-key") != "secret" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"ok":true,"result":[]}`))
	}))
	defer srv.Close()

	c := NewClient("TOKEN", srv.URL, "secret")
	body, err := c.post(context.Background(), "getUpdates", map[string]any{"timeout": 0})
	if err != nil {
		t.Fatalf("post: %v", err)
	}
	if !bytes.Contains(body, []byte(`"ok":true`)) {
		t.Fatalf("unexpected body: %s", body)
	}
	if path != "/getUpdates" {
		t.Fatalf("path = %s, want /getUpdates", path)
	}
	if proto != "HTTP/1.1" {
		t.Fatalf("request proto = %s, want HTTP/1.1", proto)
	}
}
