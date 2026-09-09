package httpapi

import (
	"encoding/json"
	"net/http"
	"time"

	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/ws"
)

// ---------- типы запросов/ответов ----------

type initiateCallRequest struct {
	CalleeID string `json:"callee_id"`
	Video    bool   `json:"video"`
}

type callSignalRequest struct {
	To      string          `json:"to"`   // получатель сигнала (id второго участника)
	Kind    string          `json:"kind"` // offer | answer | ice
	Payload json.RawMessage `json:"payload"`
}

type callStatusRequest struct {
	Status string `json:"status"` // active | ended | declined | missed
}

type callResponse struct {
	ID        string  `json:"id"`
	CallerID  string  `json:"caller_id"`
	CalleeID  string  `json:"callee_id"`
	Video     bool    `json:"video"`
	Status    string  `json:"status"`
	CreatedAt string  `json:"created_at"`
	EndedAt   *string `json:"ended_at"`
}

// callSignalEvent — сигнал, ретранслируемый через WebSocket.
type callSignalEvent struct {
	CallID  string          `json:"call_id"`
	From    string          `json:"from"`
	Kind    string          `json:"kind"`
	Payload json.RawMessage `json:"payload"`
}

// callStatusEvent — уведомление об изменении статуса звонка.
type callStatusEvent struct {
	CallID string `json:"call_id"`
	Status string `json:"status"`
}

// ---------- хэндлеры ----------

// handleInitiateCall — POST /v1/calls. Создаёт запись звонка (ringing) и
// уведомляет вызываемого через WebSocket.
func (s *Server) handleInitiateCall(w http.ResponseWriter, r *http.Request) {
	callerID := r.Context().Value(ctxUserID).(string)

	var req initiateCallRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.CalleeID == "" || req.CalleeID == callerID {
		writeError(w, http.StatusBadRequest, "invalid callee_id")
		return
	}
	if _, err := s.store.GetUserByID(r.Context(), req.CalleeID); err != nil {
		writeError(w, http.StatusNotFound, "callee not found")
		return
	}

	id, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	call := &model.Call{
		ID:        id,
		CallerID:  callerID,
		CalleeID:  req.CalleeID,
		Video:     req.Video,
		Status:    model.CallRinging,
		CreatedAt: time.Now().UTC(),
	}
	if err := s.store.SaveCall(r.Context(), call); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	resp := callToResponse(call)

	// Уведомляем вызываемого: входящий звонок.
	s.hub.Push(req.CalleeID, ws.Event{Type: "call", Data: resp})

	writeJSON(w, http.StatusCreated, resp)
}

// handleCallSignal — POST /v1/calls/{id}/signal. Ретранслирует SDP/ICE между участниками.
// Сервер не хранит сигналы — только пересылает по WebSocket.
func (s *Server) handleCallSignal(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	callID := r.PathValue("id")

	var req callSignalRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 64<<10)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.Kind != "offer" && req.Kind != "answer" && req.Kind != "ice" {
		writeError(w, http.StatusBadRequest, "invalid kind")
		return
	}

	call, err := s.store.GetCall(r.Context(), callID)
	if err != nil {
		writeError(w, http.StatusNotFound, "call not found")
		return
	}
	peer := peerOf(call, userID)
	if peer == "" {
		writeError(w, http.StatusForbidden, "not a participant")
		return
	}
	if req.To != peer {
		writeError(w, http.StatusBadRequest, "invalid to")
		return
	}

	// Ретрансляция сигнала второму участнику.
	s.hub.Push(peer, ws.Event{
		Type: "call_signal",
		Data: callSignalEvent{CallID: callID, From: userID, Kind: req.Kind, Payload: req.Payload},
	})

	writeJSON(w, http.StatusOK, map[string]string{"status": "relayed"})
}

// handleUpdateCallStatus — POST /v1/calls/{id}/status. Меняет статус звонка
// (accept/decline/end) и уведомляет второго участника.
func (s *Server) handleUpdateCallStatus(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	callID := r.PathValue("id")

	var req callStatusRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	status := model.CallStatus(req.Status)
	if status != model.CallActive && status != model.CallEnded &&
		status != model.CallDeclined && status != model.CallMissed {
		writeError(w, http.StatusBadRequest, "invalid status")
		return
	}

	call, err := s.store.GetCall(r.Context(), callID)
	if err != nil {
		writeError(w, http.StatusNotFound, "call not found")
		return
	}
	if peerOf(call, userID) == "" {
		writeError(w, http.StatusForbidden, "not a participant")
		return
	}

	if err := s.store.UpdateCallStatus(r.Context(), callID, status); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	// Уведомляем второго участника о смене статуса.
	s.hub.Push(peerOf(call, userID), ws.Event{
		Type: "call_status",
		Data: callStatusEvent{CallID: callID, Status: string(status)},
	})

	writeJSON(w, http.StatusOK, map[string]string{"status": string(status)})
}

// handleListCalls — GET /v1/calls. История звонков текущего пользователя.
func (s *Server) handleListCalls(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	calls, err := s.store.ListCallsForUser(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	out := make([]callResponse, 0, len(calls))
	for _, c := range calls {
		out = append(out, callToResponse(c))
	}
	writeJSON(w, http.StatusOK, map[string]any{"calls": out})
}

// ---------- утилиты ----------

// peerOf возвращает id второго участника звонка для данного userID, или "" если
// пользователь не участник.
func peerOf(call *model.Call, userID string) string {
	if call.CallerID == userID {
		return call.CalleeID
	}
	if call.CalleeID == userID {
		return call.CallerID
	}
	return ""
}

func callToResponse(c *model.Call) callResponse {
	var endedAt *string
	if c.EndedAt != nil {
		s := c.EndedAt.Format(time.RFC3339)
		endedAt = &s
	}
	return callResponse{
		ID:        c.ID,
		CallerID:  c.CallerID,
		CalleeID:  c.CalleeID,
		Video:     c.Video,
		Status:    string(c.Status),
		CreatedAt: c.CreatedAt.Format(time.RFC3339),
		EndedAt:   endedAt,
	}
}
