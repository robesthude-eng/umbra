package httpapi

import (
	"encoding/json"
	"net/http"
)

// Приватный поиск контактов по телефонной книге.
//
// Клиент НЕ отправляет номера в открытом виде: он нормализует номера из
// телефонной книги (те же правила, что NormalizePhone) и присылает только
// SHA-256-хэши. Сервер сравнивает их с phone_hash зарегистрированных
// пользователей и возвращает совпадения.
//
// Известное ограничение приватности: пространство телефонных номеров мало,
// поэтому хэши в принципе перебираемы. Это свойство всех схем поиска контактов
// без SGX/PSI; перебор сдерживается rate-limit'ом эндпоинта (30/мин на IP)
// и обязательной авторизацией.

type discoverRequest struct {
	Hashes []string `json:"hashes"`
}

type discoveredUser struct {
	ID          string `json:"id"`
	Username    string `json:"username"`
	DisplayName string `json:"display_name"`
	Phone       string `json:"phone"`
	PhoneHash   string `json:"phone_hash"`
}

// handleDiscoverContacts — POST /v1/contacts/discover. Принимает до 1000
// хэшей номеров из телефонной книги, возвращает зарегистрированных
// пользователей Umbra среди них.
func (s *Server) handleDiscoverContacts(w http.ResponseWriter, r *http.Request) {
	var req discoverRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if len(req.Hashes) == 0 {
		writeJSON(w, http.StatusOK, map[string]any{"matches": []discoveredUser{}})
		return
	}
	if len(req.Hashes) > 1000 {
		writeError(w, http.StatusBadRequest, "too many hashes")
		return
	}
	// Дедупликация и валидация формата.
	seen := make(map[string]bool, len(req.Hashes))
	hashes := make([]string, 0, len(req.Hashes))
	for _, h := range req.Hashes {
		if !validPhoneHash(h) {
			writeError(w, http.StatusBadRequest, "invalid hash")
			return
		}
		if !seen[h] {
			seen[h] = true
			hashes = append(hashes, h)
		}
	}

	users, err := s.store.FindUsersByPhoneHashes(r.Context(), hashes)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	out := make([]discoveredUser, 0, len(users))
	for _, u := range users {
		out = append(out, discoveredUser{
			ID:          u.ID,
			Username:    u.Username,
			DisplayName: u.DisplayName,
			Phone:       u.Phone,
			PhoneHash:   u.PhoneHash,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"matches": out})
}
