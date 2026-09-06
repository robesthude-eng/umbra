package httpapi

import (
	"encoding/json"
	"net/http"
	"testing"
)

func TestCallLifecycle(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	// alice звонит bob (голосовой)
	code, m := doReq(t, h, http.MethodPost, "/v1/calls", map[string]any{"callee_id": u["bob"].ID, "video": false}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("initiate call: %d (%v)", code, m)
	}
	callID, _ := m["id"].(string)
	if m["status"] != "ringing" {
		t.Fatalf("ожидался ringing, получен %v", m["status"])
	}
	if m["caller_id"] != u["alice"].ID || m["callee_id"] != u["bob"].ID {
		t.Fatalf("неверные участники: %v", m)
	}

	// звонок самому себе — 400
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls", map[string]any{"callee_id": u["alice"].ID}, u["alice"].Token)
	if code != http.StatusBadRequest {
		t.Fatalf("звонок себе должен дать 400, получен %d", code)
	}

	// несуществующий callee — 404
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls", map[string]any{"callee_id": "no-such"}, u["alice"].Token)
	if code != http.StatusNotFound {
		t.Fatalf("ожидался 404, получен %d", code)
	}

	// bob принимает звонок
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/status", map[string]any{"status": "active"}, u["bob"].Token)
	if code != http.StatusOK {
		t.Fatalf("accept call: %d", code)
	}

	// история звонков у bob
	code, m = doReq(t, h, http.MethodGet, "/v1/calls", nil, u["bob"].Token)
	calls, _ := m["calls"].([]any)
	if code != http.StatusOK || len(calls) != 1 {
		t.Fatalf("история bob: %d, len=%d", code, len(calls))
	}

	// alice завершает звонок
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/status", map[string]any{"status": "ended"}, u["alice"].Token)
	if code != http.StatusOK {
		t.Fatalf("end call: %d", code)
	}
}

func TestCallSignal(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	_, m := doReq(t, h, http.MethodPost, "/v1/calls", map[string]any{"callee_id": u["bob"].ID}, u["alice"].Token)
	callID, _ := m["id"].(string)

	offer := json.RawMessage(`{"type":"offer","sdp":"..."}`)

	// alice шлёт offer bob'у
	code, _ := doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/signal",
		map[string]any{"to": u["bob"].ID, "kind": "offer", "payload": offer}, u["alice"].Token)
	if code != http.StatusOK {
		t.Fatalf("signal relay: %d", code)
	}

	// неверный kind — 400
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/signal",
		map[string]any{"to": u["bob"].ID, "kind": "bogus", "payload": offer}, u["alice"].Token)
	if code != http.StatusBadRequest {
		t.Fatalf("неверный kind должен дать 400, получен %d", code)
	}

	// посторонний не может ретранслировать — 403
	carol := setupUsers(t, h, "carol")
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/signal",
		map[string]any{"to": u["bob"].ID, "kind": "offer", "payload": offer}, carol["carol"].Token)
	if code != http.StatusForbidden {
		t.Fatalf("посторонний должен получить 403, получен %d", code)
	}

	// несуществующий звонок — 404
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/nope/signal",
		map[string]any{"to": u["bob"].ID, "kind": "offer", "payload": offer}, u["alice"].Token)
	if code != http.StatusNotFound {
		t.Fatalf("ожидался 404, получен %d", code)
	}
}

func TestCallStatusPermissions(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob")

	_, m := doReq(t, h, http.MethodPost, "/v1/calls", map[string]any{"callee_id": u["bob"].ID}, u["alice"].Token)
	callID, _ := m["id"].(string)

	// посторонний не может менять статус — 403
	carol := setupUsers(t, h, "carol")
	code, _ := doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/status", map[string]any{"status": "ended"}, carol["carol"].Token)
	if code != http.StatusForbidden {
		t.Fatalf("посторонний должен получить 403, получен %d", code)
	}

	// невалидный статус — 400
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/status", map[string]any{"status": "bogus"}, u["bob"].Token)
	if code != http.StatusBadRequest {
		t.Fatalf("невалидный статус должен дать 400, получен %d", code)
	}

	// bob отклоняет
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/status", map[string]any{"status": "declined"}, u["bob"].Token)
	if code != http.StatusOK {
		t.Fatalf("decline: %d", code)
	}
}
