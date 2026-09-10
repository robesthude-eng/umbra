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

func TestGroupCallLifecycle(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob", "carol")

	// alice зовёт bob и carol одним звонком
	code, m := doReq(t, h, http.MethodPost, "/v1/calls",
		map[string]any{"callee_ids": []string{u["bob"].ID, u["carol"].ID}, "video": true}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("групповой звонок: %d (%v)", code, m)
	}
	callID, _ := m["id"].(string)
	if parts, _ := m["participants"].([]any); len(parts) != 3 {
		t.Fatalf("ожидались три участника, получено %v", m["participants"])
	}
	// Клиенты 0.8.0 читают callee_id — он должен остаться заполненным.
	if m["callee_id"] != u["bob"].ID {
		t.Fatalf("callee_id должен указывать на первого приглашённого: %v", m["callee_id"])
	}

	// carol видит звонок в истории, хотя она не callee_id
	code, m = doReq(t, h, http.MethodGet, "/v1/calls", nil, u["carol"].Token)
	calls, _ := m["calls"].([]any)
	if code != http.StatusOK || len(calls) != 1 {
		t.Fatalf("история carol: %d, len=%d", code, len(calls))
	}

	// bob и carol принимают звонок
	for _, name := range []string{"bob", "carol"} {
		code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/status", map[string]any{"status": "active"}, u[name].Token)
		if code != http.StatusOK {
			t.Fatalf("%s принимает звонок: %d", name, code)
		}
	}

	// bob выходит: остальным уходит call_status с полем from, запрос проходит
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/status", map[string]any{"status": "ended"}, u["bob"].Token)
	if code != http.StatusOK {
		t.Fatalf("bob выходит: %d", code)
	}
}

func TestGroupCallLimits(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob", "carol", "dave", "erin")

	// пятеро в звонке mesh не выдерживает — 400
	code, _ := doReq(t, h, http.MethodPost, "/v1/calls",
		map[string]any{"callee_ids": []string{u["bob"].ID, u["carol"].ID, u["dave"].ID, u["erin"].ID}}, u["alice"].Token)
	if code != http.StatusBadRequest {
		t.Fatalf("пятеро участников должны дать 400, получен %d", code)
	}

	// дубли и сам звонящий отбрасываются: остаются двое
	code, m := doReq(t, h, http.MethodPost, "/v1/calls",
		map[string]any{"callee_id": u["bob"].ID, "callee_ids": []string{u["bob"].ID, u["alice"].ID}}, u["alice"].Token)
	if code != http.StatusCreated {
		t.Fatalf("звонок с дублями: %d (%v)", code, m)
	}
	if parts, _ := m["participants"].([]any); len(parts) != 2 {
		t.Fatalf("дубли не отброшены: %v", m["participants"])
	}

	// в списке только сам звонящий — звать некого
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls",
		map[string]any{"callee_ids": []string{u["alice"].ID}}, u["alice"].Token)
	if code != http.StatusBadRequest {
		t.Fatalf("звонок себе должен дать 400, получен %d", code)
	}
}

func TestGroupCallSignalRouting(t *testing.T) {
	h := newTestServer(t)
	u := setupUsers(t, h, "alice", "bob", "carol")

	_, m := doReq(t, h, http.MethodPost, "/v1/calls",
		map[string]any{"callee_ids": []string{u["bob"].ID, u["carol"].ID}}, u["alice"].Token)
	callID, _ := m["id"].(string)

	offer := json.RawMessage(`{"type":"offer","sdp":"..."}`)

	// в mesh сигналит каждая пара, включая пару приглашённых между собой
	for _, pair := range [][2]string{{"alice", "carol"}, {"bob", "carol"}, {"carol", "bob"}} {
		code, _ := doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/signal",
			map[string]any{"to": u[pair[1]].ID, "kind": "offer", "payload": offer}, u[pair[0]].Token)
		if code != http.StatusOK {
			t.Fatalf("сигнал %s -> %s: %d", pair[0], pair[1], code)
		}
	}

	// в группе адресат обязателен: непонятно, кому из двоих сигнал
	code, _ := doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/signal",
		map[string]any{"kind": "offer", "payload": offer}, u["alice"].Token)
	if code != http.StatusBadRequest {
		t.Fatalf("сигнал без to должен дать 400, получен %d", code)
	}

	// себе сигналить нельзя
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/signal",
		map[string]any{"to": u["alice"].ID, "kind": "ice", "payload": offer}, u["alice"].Token)
	if code != http.StatusBadRequest {
		t.Fatalf("сигнал себе должен дать 400, получен %d", code)
	}

	// посторонний по-прежнему получает 403
	dave := setupUsers(t, h, "dave")
	code, _ = doReq(t, h, http.MethodPost, "/v1/calls/"+callID+"/signal",
		map[string]any{"to": u["bob"].ID, "kind": "offer", "payload": offer}, dave["dave"].Token)
	if code != http.StatusForbidden {
		t.Fatalf("посторонний должен получить 403, получен %d", code)
	}
}
