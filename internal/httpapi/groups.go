package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"time"

	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

// ---------- типы запросов/ответов ----------

type createChatRequest struct {
	Title string `json:"title"`
}

type chatResponse struct {
	ID        string `json:"id"`
	Type      string `json:"type"`
	Title     string `json:"title"`
	CreatedBy string `json:"created_by"`
	CreatedAt string `json:"created_at"`
}

type memberResponse struct {
	UserID   string `json:"user_id"`
	Role     string `json:"role"`
	JoinedAt string `json:"joined_at"`
}

type addMemberRequest struct {
	UserID string `json:"user_id"`
}

type chatMessageRequest struct {
	Ciphertext string `json:"ciphertext"` // base64
	// ExpiresIn — секунды до самоуничтожения (секретные сообщения в группе). 0 = без таймера.
	ExpiresIn int64 `json:"expires_in"`
	ClientID string `json:"client_message_id"`
}

type contactRequest struct {
	ContactID string `json:"contact_id"`
}

// typingStore — эфемерное in-memory хранилище «печатает» с TTL.
// Не пишется в БД и не логируется: метаданные «кто когда печатал» не сохраняются.
type typingStore struct {
	mu   sync.Mutex
	data map[string]map[string]time.Time // chatID -> userID -> expiresAt
}

func newTypingStore() *typingStore {
	return &typingStore{data: make(map[string]map[string]time.Time)}
}

const typingTTL = 5 * time.Second

func (t *typingStore) mark(chatID, userID string) {
	t.mu.Lock()
	defer t.mu.Unlock()
    now := time.Now()
    live := 0
    for id, members := range t.data {
        for uid, expires := range members {
            if !expires.After(now) { delete(members, uid) } else { live++ }
        }
        if len(members) == 0 { delete(t.data, id) }
    }
    // Индикатор эфемерен: при перегрузке пропускаем новый, не накапливаем память.
    if live >= 16384 {
        if _, exists := t.data[chatID][userID]; !exists { return }
    }
	m, ok := t.data[chatID]
	if !ok {
		m = make(map[string]time.Time)
		t.data[chatID] = m
	}
	m[userID] = now.Add(typingTTL)
}

func (t *typingStore) list(chatID string) []string {
	t.mu.Lock()
	defer t.mu.Unlock()
	m := t.data[chatID]
	now := time.Now()
	out := make([]string, 0)
	for uid, exp := range m {
		if now.Before(exp) {
			out = append(out, uid)
		} else {
			delete(m, uid)
		}
	}
	if len(m) == 0 {
		delete(t.data, chatID)
	}
	return out
}

// ---------- хэндлеры ----------

// handleCreateGroup / handleCreateChannel — создание чата.
func (s *Server) handleCreateChat(chatType model.ChatType) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		userID := r.Context().Value(ctxUserID).(string)

		var req createChatRequest
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
			writeError(w, http.StatusBadRequest, "invalid request body")
			return
		}
		title := strings.TrimSpace(req.Title)
		if title == "" || len(title) > 200 {
			writeError(w, http.StatusBadRequest, "invalid title")
			return
		}

		id, err := crypto.NewToken()
		if err != nil {
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		chat := &model.Chat{
			ID:        id,
			Type:      chatType,
			Title:     title,
			CreatedBy: userID,
			CreatedAt: time.Now().UTC(),
		}
		if err := s.store.CreateChat(r.Context(), chat); err != nil {
			writeError(w, http.StatusInternalServerError, "internal error")
			return
		}
		writeJSON(w, http.StatusCreated, chatToResponse(chat))
	}
}

// handleListChats — список чатов текущего пользователя.
func (s *Server) handleListChats(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	chats, err := s.store.ListChatsForUser(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	out := make([]chatResponse, 0, len(chats))
	for _, c := range chats {
		out = append(out, chatToResponse(c))
	}
	writeJSON(w, http.StatusOK, map[string]any{"chats": out})
}

// handleAddMember — добавление участника.
func (s *Server) handleAddMember(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	chatID := r.PathValue("id")

	var req addMemberRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.UserID == "" {
		writeError(w, http.StatusBadRequest, "invalid user_id")
		return
	}

	chat, err := s.store.GetChat(r.Context(), chatID)
	if err != nil {
		writeError(w, http.StatusNotFound, "chat not found")
		return
	}

	isSelf := req.UserID == userID
	me, meErr := s.store.GetMember(r.Context(), chatID, userID)

	if chat.Type == model.ChatChannel {
		// В канал подписаться может любой сам; добавлять других — только owner/admin.
		if !isSelf && (meErr != nil || !isAdminOrOwner(me.Role)) {
			writeError(w, http.StatusForbidden, "forbidden")
			return
		}
	} else {
		// В группу добавлять может только участник с ролью owner/admin.
		if meErr != nil {
			writeError(w, http.StatusNotFound, "not a member")
			return
		}
		if !isAdminOrOwner(me.Role) {
			writeError(w, http.StatusForbidden, "forbidden")
			return
		}
	}

	if _, err := s.store.GetUserByID(r.Context(), req.UserID); err != nil {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}

	role := model.RoleMember
	if err := s.store.AddMember(r.Context(), chatID, req.UserID, role); err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "already a member")
			return
		}
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "chat not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusCreated, memberResponse{UserID: req.UserID, Role: string(role), JoinedAt: time.Now().UTC().Format(time.RFC3339)})
}

// handleRemoveMember — удаление участника (только owner/admin, не owner).
func (s *Server) handleRemoveMember(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	chatID := r.PathValue("id")
	targetID := r.PathValue("user_id")

	me, err := s.store.GetMember(r.Context(), chatID, userID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not a member")
		return
	}
	if !isAdminOrOwner(me.Role) {
		writeError(w, http.StatusForbidden, "forbidden")
		return
	}

	if err := s.store.RemoveMember(r.Context(), chatID, targetID); err != nil {
		if errors.Is(err, store.ErrForbidden) {
			writeError(w, http.StatusForbidden, "cannot remove owner")
			return
		}
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "member not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "removed"})
}

// handleListMembers — список участников (только для участника).
func (s *Server) handleListMembers(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	chatID := r.PathValue("id")

	if _, err := s.store.GetMember(r.Context(), chatID, userID); err != nil {
		writeError(w, http.StatusNotFound, "not a member")
		return
	}
	members, err := s.store.ListMembers(r.Context(), chatID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	out := make([]memberResponse, 0, len(members))
	for _, m := range members {
		out = append(out, memberResponse{UserID: m.UserID, Role: string(m.Role), JoinedAt: m.JoinedAt.Format(time.RFC3339)})
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": out})
}

// handleSendChatMessage — отправка зашифрованного сообщения в чат.
// Сервер только сохраняет ciphertext и рассылает всем участникам через WS.
func (s *Server) handleSendChatMessage(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	chatID := r.PathValue("id")

	var req chatMessageRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, s.cfg.MaxMessageBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	ct, err := b64(req.Ciphertext)
	if err != nil || len(ct) == 0 || !validMessageOptions(req.ClientID, req.ExpiresIn) {
		writeError(w, http.StatusBadRequest, "invalid ciphertext")
		return
	}

	chat, err := s.store.GetChat(r.Context(), chatID)
	if err != nil {
		writeError(w, http.StatusNotFound, "chat not found")
		return
	}
	me, err := s.store.GetMember(r.Context(), chatID, userID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not a member")
		return
	}
	// В канал пишут только owner/admin; в группу — любой участник.
	if chat.Type == model.ChatChannel && !isAdminOrOwner(me.Role) {
		writeError(w, http.StatusForbidden, "forbidden")
		return
	}

	id, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	msg := &model.Message{
		ID:         id,
		SenderID:   userID,
		ChatID:     chatID,
		Ciphertext: ct,
		CreatedAt:  time.Now().UTC(),
		ClientID: req.ClientID, ExpiresIn: req.ExpiresIn,
	}
	if req.ExpiresIn > 0 {
		exp := msg.CreatedAt.Add(time.Duration(req.ExpiresIn) * time.Second)
		msg.ExpiresAt = &exp
	}
	if err := s.store.SaveMessage(r.Context(), msg); err != nil {
		if errors.Is(err, store.ErrConflict) { writeError(w, 409, "client_message_id reused with different content"); return }
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	resp := messageResponse{
		ID:         msg.ID,
		SenderID:   msg.SenderID,
		ChatID:     msg.ChatID,
		Ciphertext: req.Ciphertext,
		CreatedAt:  msg.CreatedAt.Format(time.RFC3339Nano),
		ExpiresAt:  formatTime(msg.ExpiresAt),
		ClientID: msg.ClientID,
	}

	// Рассылка всем участникам (включая отправителя — для multi-device).
	members, err := s.store.ListMembers(r.Context(), chatID)
	if err == nil && (msg.ExpiresAt == nil || msg.ExpiresAt.After(time.Now())) {
		for _, m := range members {
			s.hub.Push(m.UserID, ws.Event{Type: "message", Data: resp})
		}
	}

	writeJSON(w, http.StatusCreated, resp)
}

// handleAddContact — добавить контакт.
func (s *Server) handleAddContact(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)

	var req contactRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.ContactID == "" {
		writeError(w, http.StatusBadRequest, "invalid contact_id")
		return
	}
	if err := s.store.AddContact(r.Context(), userID, req.ContactID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"contact_id": req.ContactID})
}

// handleListContacts — список контактов.
func (s *Server) handleListContacts(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	contacts, err := s.store.ListContacts(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"contacts": contacts})
}

// handleMarkTyping — отметить «печатает» (эфемерно).
func (s *Server) handleMarkTyping(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	chatID := r.PathValue("id")
	if _, err := s.store.GetMember(r.Context(), chatID, userID); err != nil {
		writeError(w, http.StatusNotFound, "not a member")
		return
	}
	s.typing.mark(chatID, userID)
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleListTyping — кто сейчас печатает.
func (s *Server) handleListTyping(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	chatID := r.PathValue("id")
	if _, err := s.store.GetMember(r.Context(), chatID, userID); err != nil {
		writeError(w, http.StatusNotFound, "not a member")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"typing": s.typing.list(chatID)})
}

// ---------- утилиты ----------

func isAdminOrOwner(role model.MemberRole) bool {
	return role == model.RoleOwner || role == model.RoleAdmin
}

func chatToResponse(c *model.Chat) chatResponse {
	return chatResponse{
		ID:        c.ID,
		Type:      string(c.Type),
		Title:     c.Title,
		CreatedBy: c.CreatedBy,
		CreatedAt: c.CreatedAt.Format(time.RFC3339),
	}
}
