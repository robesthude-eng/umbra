package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

// readCursorView — «прочитано до» в личной переписке. Пустой read_at —
// «ещё не читал» либо скрытый статус.
type readCursorView struct {
	UserID string `json:"user_id"`
	ReadAt string `json:"read_at,omitempty"`
	Hidden bool   `json:"hidden"`
}

type readRequest struct {
	ReadAt string `json:"read_at,omitempty"`
}

// handleMarkRead — POST /v1/chats/{id}/read, где id — собеседник личного чата.
// Группы сюда не ходят: там нужен курсор на каждого участника.
func (s *Server) handleMarkRead(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	peer := r.PathValue("id")
	if peer == "" || peer == userID {
		writeError(w, http.StatusBadRequest, "invalid chat id")
		return
	}
	if _, err := s.store.GetUserByID(r.Context(), peer); err != nil {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}
	var body readRequest
	if r.Body != nil {
		// Тело необязательно: пустой запрос означает «прочитано сейчас».
		_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&body)
	}
	at := time.Now().UTC()
	if body.ReadAt != "" {
		// Клиент может указать момент прочтения, но не будущее.
		if parsed, err := time.Parse(time.RFC3339, body.ReadAt); err == nil && parsed.Before(at) {
			at = parsed.UTC()
		}
	}
	if err := s.store.SetReadCursor(r.Context(), userID, peer, at); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "read receipts temporarily unavailable")
		}
		return
	}
	// Скрывший «был(а) в сети» не сообщает и о прочтении: одна настройка на оба статуса.
	if _, hidden, err := s.store.GetPresence(r.Context(), userID); err == nil && !hidden {
		s.hub.Push(peer, ws.Event{Type: "read", Data: map[string]string{
			"chat_id": userID,
			"read_at": at.Format(time.RFC3339),
		}})
	}
	writeJSON(w, http.StatusOK, readCursorView{UserID: userID, ReadAt: at.Format(time.RFC3339)})
}

// handleGetRead — GET /v1/chats/{id}/read: до какого момента собеседник
// прочитал переписку со мной. Запасной путь, если push не дошёл.
func (s *Server) handleGetRead(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	peer := r.PathValue("id")
	if peer == "" || peer == userID {
		writeError(w, http.StatusBadRequest, "invalid chat id")
		return
	}
	at, err := s.store.GetReadCursor(r.Context(), peer, userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "read receipts temporarily unavailable")
		}
		return
	}
	// Взаимность как у «был(а) в сети»: скрывший свой статус не видит чужой.
	hidden := false
	if _, h, err := s.store.GetPresence(r.Context(), peer); err == nil {
		hidden = h
	}
	if !hidden {
		if _, h, err := s.store.GetPresence(r.Context(), userID); err == nil {
			hidden = h
		}
	}
	out := readCursorView{UserID: peer, Hidden: hidden}
	if !hidden && !at.IsZero() {
		out.ReadAt = at.UTC().Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, out)
}
