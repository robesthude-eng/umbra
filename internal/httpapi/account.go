package httpapi

import (
	"net/http"
	"time"
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

	// Инвалидируем все токены пользователя (даже если DeleteUser уже удалил их в БД,
	// на случай in-memory кэша). Здесь просто возвращаем подтверждение.
	writeJSON(w, http.StatusOK, map[string]any{
		"status":     "burned",
		"deleted_at": time.Now().UTC().Format(time.RFC3339),
	})
}

// handleGetAccount — GET /v1/account. Возвращает базовую информацию о текущем
// пользователе (без чувствительных данных).
func (s *Server) handleGetAccount(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	u, err := s.store.GetUserByID(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id":         u.ID,
		"username":   u.Username,
		"created_at": u.CreatedAt.Format(time.RFC3339),
	})
}
