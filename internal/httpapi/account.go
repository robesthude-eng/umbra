package httpapi

import (
	"context"
	"errors"
	"net/http"
	"time"
	"umbra/server/internal/crypto"
	"umbra/server/internal/maintenance"
	"umbra/server/internal/store"
)

// handleBurnAccount — POST /v1/account/burn. Полностью удаляет аккаунт текущего
// пользователя и все его данные: ключи, сообщения, медиа, чаты, контакты, звонки.
// Операция необратима. Токен после этого становится невалидным.
func (s *Server) handleBurnAccount(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)

	if err := s.store.DeleteUser(r.Context(), userID); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	s.hub.DisconnectUser(userID)
	cleanup, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	cleanupErr := maintenance.DrainBlobs(cleanup, s.store, s.blobs)
	pending, pendingErr := s.store.PendingBlobDeletes(r.Context())

	// Инвалидируем все токены пользователя (даже если DeleteUser уже удалил их в БД,
	// на случай in-memory кэша). Здесь просто возвращаем подтверждение.
	writeJSON(w, http.StatusOK, map[string]any{
		"status":          "burned",
		"deleted_at":      time.Now().UTC().Format(time.RFC3339),
		"cleanup_pending": cleanupErr != nil || pendingErr != nil || len(pending) != 0,
	})
}

// handleGetAccount — GET /v1/account. Возвращает базовую информацию о текущем
// пользователе (без чувствительных данных).
func (s *Server) handleGetAccount(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	u, err := s.store.GetUserByID(r.Context(), userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "account temporarily unavailable")
		}
		return
	}
	count, err := s.store.OneTimePrekeyCount(r.Context(), userID)
	if err != nil {
		writeError(w, 500, "internal error")
		return
	}
	avatar, _ := s.store.GetAvatar(r.Context(), userID)
	writeJSON(w, http.StatusOK, map[string]any{
		"id":                    u.ID,
		"username":              u.Username,
		"phone":                 u.Phone,
		"display_name":          u.DisplayName,
		"last_name":             u.LastName,
		"created_at":            u.CreatedAt.UTC().Format(time.RFC3339),
		"key_version":           u.KeyVersion,
		"one_time_prekey_count": count,
		"avatar_media_id":       avatar,
	})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	token, _ := bearerToken(r)
	if err := s.store.DeleteToken(r.Context(), crypto.HashToken(token)); err != nil {
		writeError(w, 500, "internal error")
		return
	}
	// Закрываем соединения аккаунта; другие сессии смогут переподключиться.
	s.hub.DisconnectUser(r.Context().Value(ctxUserID).(string))
	writeJSON(w, 200, map[string]string{"status": "logged_out"})
}

// Renew the same credential so a lost HTTP response cannot lose the session.
// The store checks expiry/revocation again in the same operation as the update.
func (s *Server) handleRefreshSession(w http.ResponseWriter, r *http.Request) {
	token, _ := bearerToken(r)
	userID := r.Context().Value(ctxUserID).(string)
	expires, err := s.store.RenewToken(r.Context(), crypto.HashToken(token), userID, time.Now().Add(s.cfg.TokenTTL))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusUnauthorized, "unauthorized")
		} else {
			writeError(w, http.StatusServiceUnavailable, "session renewal temporarily unavailable")
		}
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, map[string]string{"expires_at": expires.UTC().Format(time.RFC3339Nano)})
}
