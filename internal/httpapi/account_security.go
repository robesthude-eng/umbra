package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"time"
	"umbra/server/internal/crypto"
	"umbra/server/internal/push"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

func writeOTPError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, otpErrTooMany):
		w.Header().Set("Retry-After", "1800")
		writeError(w, 429, "Лимит попыток исчерпан. Запросите новый код позже.")
	case errors.Is(err, otpErrInvalid):
		writeError(w, 401, "Неверный код")
	default:
		writeError(w, 404, "Код не найден или истёк. Запросите новый.")
	}
}
func (s *Server) handleSecurityCode(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Purpose string `json:"purpose"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil || req.Purpose != "delete_account" {
		writeError(w, 400, "invalid purpose")
		return
	}
	id := r.Context().Value(ctxUserID).(string)
	u, err := s.store.GetUserByID(r.Context(), id)
	if err != nil {
		writeError(w, 503, "service unavailable")
		return
	}
	if u.Phone == "" {
		writeError(w, 403, "Для удаления аккаунта без номера обратитесь к владельцу сервера")
		return
	}
	token, _ := bearerToken(r)
	hash := crypto.HashToken(token)
	sessions, err := s.store.ListSessions(r.Context(), id)
	if err != nil {
		writeError(w, 503, "service unavailable")
		return
	}
	device := ""
	for _, session := range sessions {
		if session.TokenHash == hash {
			device = session.DeviceName
			break
		}
	}
	if device == "" {
		writeError(w, 401, "unauthorized")
		return
	}
	s.sendCode(w, r, u.Phone, "delete_account", hash, device, false)
}
func (s *Server) confirmAccountDeletion(w http.ResponseWriter, r *http.Request, userID string) bool {
	var req struct {
		RequestID string `json:"request_id"`
		Code      string `json:"code"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil || req.RequestID == "" || len(req.Code) != 6 {
		writeError(w, 400, "Для удаления требуется отдельный код подтверждения")
		return false
	}
	u, err := s.store.GetUserByID(r.Context(), userID)
	if err != nil {
		writeError(w, 503, "service unavailable")
		return false
	}
	token, _ := bearerToken(r)
	if _, err := s.otp.verify(u.Phone, "delete_account", crypto.HashToken(token), req.RequestID, req.Code); err != nil {
		if errors.Is(err, otpErrInvalid) {
			writeError(w, 400, "Неверный код удаления")
		} else {
			writeOTPError(w, err)
		}
		return false
	}
	return true
}
func (s *Server) handleListSessions(w http.ResponseWriter, r *http.Request) {
	id := r.Context().Value(ctxUserID).(string)
	sessions, err := s.store.ListSessions(r.Context(), id)
	if err != nil {
		writeError(w, 503, "service unavailable")
		return
	}
	token, _ := bearerToken(r)
	hash := crypto.HashToken(token)
	for i := range sessions {
		sessions[i].Current = sessions[i].TokenHash == hash
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, 200, map[string]any{"sessions": sessions})
}
func (s *Server) handleRevokeSession(w http.ResponseWriter, r *http.Request) {
	id := r.Context().Value(ctxUserID).(string)
	if err := s.store.RevokeSession(r.Context(), id, r.PathValue("id")); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, 404, "session not found")
		} else {
			writeError(w, 503, "service unavailable")
		}
		return
	}
	s.hub.DisconnectUser(id)
	writeJSON(w, 200, map[string]string{"status": "revoked"})
}
func (s *Server) handleRevokeOtherSessions(w http.ResponseWriter, r *http.Request) {
	id := r.Context().Value(ctxUserID).(string)
	token, _ := bearerToken(r)
	if err := s.store.RevokeOtherSessions(r.Context(), id, crypto.HashToken(token)); err != nil {
		writeError(w, 503, "service unavailable")
		return
	}
	s.hub.DisconnectUser(id)
	writeJSON(w, 200, map[string]string{"status": "revoked"})
}
func (s *Server) notifyNewSession(userID, sessionID, device string, created time.Time) {
	data := map[string]string{"kind": "security", "user_id": userID, "session_id": sessionID, "device_name": device, "created_at": created.Format(time.RFC3339)}
	if s.hub != nil {
		s.hub.Push(userID, ws.Event{Type: "security", Data: data})
	}
	if s.pusher == nil {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), pushTimeout)
		defer cancel()
		devices, err := s.store.ListPushDevices(ctx, userID)
		if err != nil {
			return
		}
		for _, d := range devices {
			if d.SessionID == sessionID {
				continue
			}
			_ = s.pusher.Send(ctx, push.Message{Token: d.Token, Data: data, CollapseKey: "umbra-security", TTL: messagePushTTL, HighPriority: true})
		}
	}()
}
