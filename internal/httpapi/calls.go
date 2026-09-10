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
	CalleeID  string   `json:"callee_id"`  // личный звонок (клиенты 0.8.0)
	CalleeIDs []string `json:"callee_ids"` // групповой: до 4 участников вместе со звонящим
	Video     bool     `json:"video"`
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
	ID           string   `json:"id"`
	CallerID     string   `json:"caller_id"`
	CalleeID     string   `json:"callee_id"`
	Participants []string `json:"participants"`
	Video        bool     `json:"video"`
	Status       string   `json:"status"`
	CreatedAt    string   `json:"created_at"`
	EndedAt      *string  `json:"ended_at"`
}

// callSignalEvent — сигнал, ретранслируемый через WebSocket.
type callSignalEvent struct {
	CallID  string          `json:"call_id"`
	From    string          `json:"from"`
	Kind    string          `json:"kind"`
	Payload json.RawMessage `json:"payload"`
}

// callStatusEvent — уведомление об изменении статуса звонка. В группе важно,
// кто именно сменил статус: ended от одного участника — это его выход,
// а не конец всего звонка.
type callStatusEvent struct {
	CallID string `json:"call_id"`
	Status string `json:"status"`
	From   string `json:"from"`
}

// ---------- хэндлеры ----------

// handleInitiateCall — POST /v1/calls. Создаёт запись звонка (ringing) и
// уведомляет приглашённых через WebSocket.
//
// Личный звонок присылает callee_id, групповой — callee_ids: до 4 участников
// вместе со звонящим, медиа идёт mesh-схемой: каждый соединяется с каждым.
func (s *Server) handleInitiateCall(w http.ResponseWriter, r *http.Request) {
	callerID := r.Context().Value(ctxUserID).(string)

	var req initiateCallRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	// Собираем приглашённых из обоих полей: без пустых, дублей и самого звонящего.
	callees := make([]string, 0, model.MaxCallParticipants)
	seen := map[string]bool{callerID: true}
	for _, peer := range append([]string{req.CalleeID}, req.CalleeIDs...) {
		if peer == "" || seen[peer] {
			continue
		}
		seen[peer] = true
		callees = append(callees, peer)
	}
	if len(callees) == 0 {
		writeError(w, http.StatusBadRequest, "invalid callee_id")
		return
	}
	if len(callees)+1 > model.MaxCallParticipants {
		writeError(w, http.StatusBadRequest, "too many participants")
		return
	}
	for _, peer := range callees {
		if _, err := s.store.GetUserByID(r.Context(), peer); err != nil {
			writeError(w, http.StatusNotFound, "callee not found")
			return
		}
	}

	id, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	// CalleeID заполняем и в группе (первый приглашённый): так продолжают
	// работать клиенты 0.8.0 и не нарушается NOT NULL в базе.
	call := &model.Call{
		ID:           id,
		CallerID:     callerID,
		CalleeID:     callees[0],
		Participants: append([]string{callerID}, callees...),
		Video:        req.Video,
		Status:       model.CallRinging,
		CreatedAt:    time.Now().UTC(),
	}
	if err := s.store.SaveCall(r.Context(), call); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	resp := callToResponse(call)

	for _, peer := range callees {
		// Уведомляем приглашённого: входящий звонок.
		s.hub.Push(peer, ws.Event{Type: "call", Data: resp})
		// Телефон может быть с закрытым приложением — будим его уведомлением.
		s.notifyIncomingCall(call, peer)
	}

	writeJSON(w, http.StatusCreated, resp)
}

// handleCallSignal — POST /v1/calls/{id}/signal. Ретранслирует SDP/ICE между участниками.
// Сервер не хранит сигналы — только пересылает по WebSocket. В групповом
// звонке поле to обязательно: оно говорит, кому из mesh-соседей адресован сигнал.
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
	others := call.Others(userID)
	if others == nil {
		writeError(w, http.StatusForbidden, "not a participant")
		return
	}
	to := req.To
	if to == "" && len(others) == 1 {
		// Клиенты 0.8.0 не заполняют to в личном звонке — адресат там один.
		to = others[0]
	}
	if !containsID(others, to) {
		writeError(w, http.StatusBadRequest, "invalid to")
		return
	}

	// Ретрансляция сигнала конкретному участнику: в mesh каждая пара своя.
	s.hub.Push(to, ws.Event{
		Type: "call_signal",
		Data: callSignalEvent{CallID: callID, From: userID, Kind: req.Kind, Payload: req.Payload},
	})

	writeJSON(w, http.StatusOK, map[string]string{"status": "relayed"})
}

// handleUpdateCallStatus — POST /v1/calls/{id}/status. Меняет статус звонка
// (accept/decline/end) и уведомляет остальных участников.
//
// В группе статус общий для записи, а событие несёт поле from: клиент понимает
// «ушёл один участник» и завершает звонок, только когда рядом никого не осталось.
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
	others := call.Others(userID)
	if others == nil {
		writeError(w, http.StatusForbidden, "not a participant")
		return
	}

	if err := s.store.UpdateCallStatus(r.Context(), callID, status); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	ended := status == model.CallEnded || status == model.CallDeclined || status == model.CallMissed
	for _, peer := range others {
		// Уведомляем остальных участников о смене статуса.
		s.hub.Push(peer, ws.Event{
			Type: "call_status",
			Data: callStatusEvent{CallID: callID, Status: string(status), From: userID},
		})
		// Гасим экран входящего на телефоне, который разбудили звонком.
		if ended {
			s.notifyCallEnded(peer, callID, string(status))
		}
	}

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

// containsID — есть ли id в списке участников.
func containsID(ids []string, id string) bool {
	if id == "" {
		return false
	}
	for _, cur := range ids {
		if cur == id {
			return true
		}
	}
	return false
}

func callToResponse(c *model.Call) callResponse {
	var endedAt *string
	if c.EndedAt != nil {
		s := c.EndedAt.Format(time.RFC3339)
		endedAt = &s
	}
	return callResponse{
		ID:           c.ID,
		CallerID:     c.CallerID,
		CalleeID:     c.CalleeID,
		Participants: c.Everyone(),
		Video:        c.Video,
		Status:       string(c.Status),
		CreatedAt:    c.CreatedAt.Format(time.RFC3339),
		EndedAt:      endedAt,
	}
}
