package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
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
	// Занятость: раньше второй звонок тому же человеку создавал вторую
	// запись и второй экран входящего. Себя тоже проверяем: двойное
	// нажатие «позвонить» давало два параллельных потока сигналинга.
	for _, peer := range append([]string{callerID}, callees...) {
		busy, err := s.hasRingingCall(r.Context(), peer)
		if err != nil {
			writeError(w, http.StatusServiceUnavailable, "service unavailable")
			return
		}
		if busy {
			if peer == callerID {
				writeError(w, http.StatusConflict, "already calling")
			} else {
				writeError(w, http.StatusConflict, "callee is busy")
			}
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
	// Законченный звонок сигналов больше не принимает: раньше offer/ice
	// можно было шлать в любой момент жизни записи и будить чужой клиент.
	if call.Status != model.CallRinging && call.Status != model.CallActive {
		writeError(w, http.StatusConflict, "call is not active")
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
	// Завершённый звонок не меняет статус: оживлять ended в active или
	// переписывать declined в missed нельзя. Повтор того же статуса
	// идемпотентен: клиент повторяет ended при обрыве сети.
	if call.Status == model.CallEnded || call.Status == model.CallDeclined || call.Status == model.CallMissed {
		if status == call.Status {
			writeJSON(w, http.StatusOK, map[string]string{"status": string(status)})
			return
		}
		writeError(w, http.StatusConflict, "call already finished")
		return
	}

	ended := status == model.CallEnded || status == model.CallDeclined || status == model.CallMissed
	// В группе выход одного участника не заканчивает разговор: звук идёт
	// напрямую между телефонами. Раньше первый же ended/declined закрывал
	// запись и гасил звонок у всех остальных.
	remaining := len(call.Everyone())
	if ended {
		remaining = s.callLeavers.leave(callID, userID, call.Everyone())
	}
	// Звонящий отменил вызов до ответа — вызов снят у всех сразу.
	cancelled := ended && userID == call.CallerID && call.Status == model.CallRinging
	// Статус касается всего звонка, а не одного участника.
	whole := !ended || cancelled || remaining <= 1
	if whole {
		if err := s.store.UpdateCallStatus(r.Context(), callID, status); err != nil {
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		s.callLeavers.forget(callID)
	}

	for _, peer := range others {
		// Уведомляем остальных участников о смене статуса.
		s.hub.Push(peer, ws.Event{
			Type: "call_status",
			Data: callStatusEvent{CallID: callID, Status: string(status), From: userID},
		})
		// Гасим экран входящего только когда звонок закончился для всех:
		// иначе отказ одного участника снимал вызов у остальных.
		if ended && whole {
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

// ringingBusyWindow — сколько времени вызов считается звонящим. Клиент
// снимает неотвеченный вызов через 45 с, а maintenance переводит забытые
// записи в missed, поэтому зависший ringing блокирует новые звонки
// не дольше этого окна. Разговор (active) занятостью не считаем: убитый
// процесс не присылает ended, и человек остался бы без звонков надолго.
const ringingBusyWindow = 60 * time.Second

// hasRingingCall — звонит ли у человека вызов прямо сейчас.
func (s *Server) hasRingingCall(ctx context.Context, userID string) (bool, error) {
	calls, err := s.store.ListCallsForUser(ctx, userID)
	if err != nil {
		return false, err
	}
	since := time.Now().UTC().Add(-ringingBusyWindow)
	for _, c := range calls {
		if c.Status == model.CallRinging && c.CreatedAt.After(since) {
			return true, nil
		}
	}
	return false, nil
}

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
		s := c.EndedAt.UTC().Format(time.RFC3339)
		endedAt = &s
	}
	return callResponse{
		ID:           c.ID,
		CallerID:     c.CallerID,
		CalleeID:     c.CalleeID,
		Participants: c.Everyone(),
		Video:        c.Video,
		Status:       string(c.Status),
		CreatedAt:    c.CreatedAt.UTC().Format(time.RFC3339),
		EndedAt:      endedAt,
	}
}

// ---------- учёт ушедших из звонка ----------

// callLeaverTTL — сколько держим след незакрытого звонка.
const callLeaverTTL = 6 * time.Hour

// callLeaverStore помнит, кто уже вышел из звонка.
//
// Групповой звонок идёт напрямую между телефонами, а в записи звонка
// статус один на всех. Чтобы выход одного человека не закрывал разговор
// остальным, сервер держит список ушедших в памяти процесса: эти данные
// нужны только на время разговора, а история звонка остаётся в хранилище.
// Перезапуск сервера в середине звонка лишь возвращает старое поведение
// для ещё идущих разговоров и ничего не теряет.
type callLeaverStore struct {
	mu    sync.Mutex
	calls map[string]*callLeaveState
}

type callLeaveState struct {
	left    map[string]struct{}
	touched time.Time
}

func newCallLeaverStore() *callLeaverStore {
	return &callLeaverStore{calls: make(map[string]*callLeaveState)}
}

// leave отмечает выход участника и возвращает, сколько людей осталось
// в звонке. Повторный вызов для того же участника ничего не меняет.
func (s *callLeaverStore) leave(callID, userID string, everyone []string) int {
	if s == nil {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.evictLocked()
	entry := s.calls[callID]
	if entry == nil {
		entry = &callLeaveState{left: make(map[string]struct{})}
		s.calls[callID] = entry
	}
	entry.left[userID] = struct{}{}
	entry.touched = time.Now()
	remaining := 0
	for _, id := range everyone {
		if _, gone := entry.left[id]; !gone {
			remaining++
		}
	}
	return remaining
}

// forget убирает законченный звонок из памяти.
func (s *callLeaverStore) forget(callID string) {
	if s == nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.calls, callID)
}

// evictLocked чистит записи забытых звонков, если кто-то так и не вышел.
func (s *callLeaverStore) evictLocked() {
	deadline := time.Now().Add(-callLeaverTTL)
	for id, entry := range s.calls {
		if entry.touched.Before(deadline) {
			delete(s.calls, id)
		}
	}
}
