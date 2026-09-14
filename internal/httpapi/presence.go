package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"sync"
	"time"

	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

// presenceView — ответ клиенту. last_seen_at пуст, если собеседник скрыл
// статус или ещё ни разу не заходил после обновления сервера.
type presenceView struct {
	UserID     string `json:"user_id"`
	Online     bool   `json:"online"`
	LastSeenAt string `json:"last_seen_at,omitempty"`
	Hidden     bool   `json:"hidden"`
}

type privacyRequest struct {
	HideLastSeen bool `json:"hide_last_seen"`
}

// Дебаунс записи: пишем не чаще раза в 30 с на пользователя, иначе любое
// поллинг-соединение било бы UPDATE на каждый запрос.
var (
	presenceTouches   sync.Map
	presenceTouchGap  = 30 * time.Second
	presenceOnlineGap = 70 * time.Second
)

func (s *Server) touchPresence(userID string) {
	if userID == "" {
		return
	}
	now := time.Now()
	if prev, ok := presenceTouches.Load(userID); ok {
		if at, fine := prev.(time.Time); fine && now.Sub(at) < presenceTouchGap {
			return
		}
	}
	presenceTouches.Store(userID, now)
	// Короткий фоновый контекст: запись не должна жить дольше запроса.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = s.store.TouchPresence(ctx, userID, now)
}

// presenceFanoutLimit — верхняя граница рассылки статуса. Статус важен в личной
// переписке; рассылать его в каждую крупную группу бессмысленно дорого.
const presenceFanoutLimit = 8

// broadcastPresence толкает смену статуса собеседникам по WebSocket, чтобы
// клиенту не приходилось опрашивать /presence по таймеру. Ошибки глотаем:
// опрос остаётся запасным путём.
func (s *Server) broadcastPresence(userID string, online bool) {
	if userID == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	lastSeen, hidden, err := s.store.GetPresence(ctx, userID)
	if err != nil || hidden {
		// Скрывший статус не рассылает его вовсе.
		return
	}
	chats, err := s.store.ListChatsForUser(ctx, userID)
	if err != nil {
		return
	}
	seen := make(map[string]bool, len(chats))
	for _, chat := range chats {
		if chat == nil {
			continue
		}
		members, err := s.store.ListMembers(ctx, chat.ID)
		if err != nil || len(members) > presenceFanoutLimit {
			continue
		}
		for _, m := range members {
			if m == nil || m.UserID == userID || seen[m.UserID] {
				continue
			}
			seen[m.UserID] = true
			if !s.hub.Online(m.UserID) {
				continue
			}
			data := map[string]string{
				"user_id": userID,
				"online":  strconv.FormatBool(online),
				"hidden":  "false",
			}
			if !lastSeen.IsZero() {
				data["last_seen_at"] = lastSeen.UTC().Format(time.RFC3339)
			}
			if online {
				data["last_seen_at"] = time.Now().UTC().Format(time.RFC3339)
			}
			s.hub.Push(m.UserID, ws.Event{Type: "presence", Data: data})
		}
	}
}

// handleGetPresence — GET /v1/users/{user_id}/presence.
func (s *Server) handleGetPresence(w http.ResponseWriter, r *http.Request) {
	viewer := r.Context().Value(ctxUserID).(string)
	id := r.PathValue("user_id")
	if id == "" {
		writeError(w, http.StatusBadRequest, "invalid user_id")
		return
	}
	lastSeen, hidden, err := s.store.GetPresence(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "presence temporarily unavailable")
		}
		return
	}
	// Взаимность: скрывший своё время не видит время других.
	viewerHidden := false
	if viewer != id {
		if _, vh, err := s.store.GetPresence(r.Context(), viewer); err == nil {
			viewerHidden = vh
		}
	}
	out := presenceView{UserID: id, Hidden: hidden || viewerHidden}
	if out.Hidden && viewer != id {
		writeJSON(w, http.StatusOK, out)
		return
	}
	out.Online = s.hub.Online(id) || time.Since(lastSeen) < presenceOnlineGap
	if !lastSeen.IsZero() {
		out.LastSeenAt = lastSeen.UTC().Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, out)
}

// handleUpdatePrivacy — POST /v1/account/privacy.
func (s *Server) handleUpdatePrivacy(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	var body privacyRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid body")
		return
	}
	if err := s.store.SetPresenceHidden(r.Context(), userID, body.HideLastSeen); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "presence temporarily unavailable")
		}
		return
	}
	writeJSON(w, http.StatusOK, privacyRequest{HideLastSeen: body.HideLastSeen})
}
