package httpapi

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"umbra/server/internal/store"
)

// userCard — публичная карточка пользователя для клиентов (диалоги, группы,
// аватары). Телефон не отдаём: для имени и аватара достаточно остальных полей.
type userCard struct {
	ID            string `json:"id"`
	Username      string `json:"username"`
	DisplayName   string `json:"display_name"`
	LastName      string `json:"last_name,omitempty"`
	AvatarMediaID string `json:"avatar_media_id,omitempty"`
	CreatedAt     string `json:"created_at"`
}

// handleGetUserByUsername — GET /v1/by-username/{username}. Разрешает
// @username в карточку (нужно для «новый диалог по имени пользователя»).
func (s *Server) handleGetUserByUsername(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(strings.TrimSpace(r.PathValue("username")), "@")
	if !validUsername(name) {
		writeError(w, http.StatusBadRequest, "invalid username")
		return
	}
	u, err := s.store.GetUserByUsername(r.Context(), name)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "account temporarily unavailable")
		}
		return
	}
	avatar, _ := s.store.GetAvatar(r.Context(), u.ID)
	writeJSON(w, http.StatusOK, userCard{
		ID:            u.ID,
		Username:      u.Username,
		DisplayName:   u.DisplayName,
		LastName:      u.LastName,
		AvatarMediaID: avatar,
		CreatedAt:     u.CreatedAt.UTC().Format(time.RFC3339),
	})
}

// handleGetUser — GET /v1/users/{user_id}. Карточка произвольного пользователя
// (имя/аватар собеседника в диалоге и в списке участников группы). Требует
// авторизации, но не дружбы: в семейной сети это ок.
func (s *Server) handleGetUser(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("user_id")
	if id == "" {
		writeError(w, http.StatusBadRequest, "invalid user_id")
		return
	}
	u, err := s.store.GetUserByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "account temporarily unavailable")
		}
		return
	}
	avatar, _ := s.store.GetAvatar(r.Context(), id)
	writeJSON(w, http.StatusOK, userCard{
		ID:            u.ID,
		Username:      u.Username,
		DisplayName:   u.DisplayName,
		LastName:      u.LastName,
		AvatarMediaID: avatar,
		CreatedAt:     u.CreatedAt.UTC().Format(time.RFC3339),
	})
}
