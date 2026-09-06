package httpapi

import (
	"encoding/base64"
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

	"github.com/gorilla/websocket"
)

// ---------- вспомогательные структуры запросов/ответов ----------

type registerRequest struct {
	Username        string   `json:"username"`
	IdentityEd25519 string   `json:"identity_ed25519"` // base64
	IdentityX25519  string   `json:"identity_x25519"`  // base64
	SignedPrekey    string   `json:"signed_prekey"`    // base64
	SignedPrekeySig string   `json:"signed_prekey_signature"`
	OneTimePrekeys  []string `json:"one_time_prekeys"` // base64
}

type prekeysResponse struct {
	ID              string `json:"id"`
	Username        string `json:"username"`
	IdentityEd25519 string `json:"identity_ed25519"`
	IdentityX25519  string `json:"identity_x25519"`
	SignedPrekey    string `json:"signed_prekey"`
	SignedPrekeySig string `json:"signed_prekey_signature"`
	OneTimePrekey   string `json:"one_time_prekey"`
}

type challengeRequest struct {
	Username string `json:"username"`
}

type challengeResponse struct {
	Challenge string `json:"challenge"`
}

type verifyRequest struct {
	Username  string `json:"username"`
	Challenge string `json:"challenge"`
	Signature string `json:"signature"` // ed25519-подпись challenge, base64
}

type verifyResponse struct {
	Token     string `json:"token"`
	ExpiresAt string `json:"expires_at"`
}

type sendMessageRequest struct {
	RecipientID string `json:"recipient_id"`
	Ciphertext  string `json:"ciphertext"` // base64
}

type messageResponse struct {
	ID          string `json:"id"`
	SenderID    string `json:"sender_id"`
	RecipientID string `json:"recipient_id"`
	ChatID      string `json:"chat_id"`
	Ciphertext  string `json:"ciphertext"`
	CreatedAt   string `json:"created_at"`
}

// challengeStore — in-memory хранилище одноразовых challenge (nonce) с TTL.
// Ограничивает время жизни nonce и не даёт повторно использовать подпись.
type challengeStore struct {
	mu   sync.Mutex
	data map[string]time.Time
}

func newChallengeStore() *challengeStore { return &challengeStore{data: make(map[string]time.Time)} }

func (c *challengeStore) put(challenge string, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.data[challenge] = time.Now().Add(ttl)
}

func (c *challengeStore) consume(challenge string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	exp, ok := c.data[challenge]
	if !ok {
		return false
	}
	delete(c.data, challenge)
	return time.Now().Before(exp)
}

// ---------- хэндлеры ----------

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleRegister — регистрация пользователя по username + открытые ключи.
// НИКАКИХ паролей и номеров телефонов — только публичные ключи.
func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	username := strings.TrimSpace(req.Username)
	if !validUsername(username) {
		writeError(w, http.StatusBadRequest, "invalid username")
		return
	}

	idKey, err := b64(req.IdentityEd25519)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid identity_ed25519")
		return
	}
	ix, err := b64(req.IdentityX25519)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid identity_x25519")
		return
	}
	spk, err := b64(req.SignedPrekey)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid signed_prekey")
		return
	}
	spsig, err := b64(req.SignedPrekeySig)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid signed_prekey_signature")
		return
	}
	var otks [][]byte
	for _, v := range req.OneTimePrekeys {
		b, err := b64(v)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid one_time_prekey")
			return
		}
		otks = append(otks, b)
	}

	userID, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	u := &model.User{
		ID:              userID,
		Username:        username,
		IdentityEd25519: idKey,
		IdentityX25519:  ix,
		SignedPrekey:    spk,
		SignedPrekeySig: spsig,
		OneTimePrekeys:  otks,
		CreatedAt:       time.Now().UTC(),
	}
	if err := s.store.CreateUser(r.Context(), u); err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "username already taken")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"id": userID, "username": username})
}

// handlePrekeys — отдаёт публичный ключевой материал пользователя для X3DH.
func (s *Server) handlePrekeys(w http.ResponseWriter, r *http.Request) {
	username := r.PathValue("username")
	u, err := s.store.GetUserByUsername(r.Context(), username)
	if err != nil {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}
	otk, err := s.store.TakeOneTimePrekey(r.Context(), u.ID)
	if err != nil {
		// Нет одноразовых pre-keys — отдаём без него (клиент использует signed pre-key).
		otk = nil
	}
	writeJSON(w, http.StatusOK, prekeysResponse{
		ID:              u.ID,
		Username:        u.Username,
		IdentityEd25519: b64e(u.IdentityEd25519),
		IdentityX25519:  b64e(u.IdentityX25519),
		SignedPrekey:    b64e(u.SignedPrekey),
		SignedPrekeySig: b64e(u.SignedPrekeySig),
		OneTimePrekey:   b64e(otk),
	})
}

// handleAuthChallenge — выдаёт nonce для подписи (challenge-response).
func (s *Server) handleAuthChallenge(w http.ResponseWriter, r *http.Request) {
	var req challengeRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if _, err := s.store.GetUserByUsername(r.Context(), strings.TrimSpace(req.Username)); err != nil {
		// Не раскрываем, существует ли пользователь — всегда отвечаем одинаково.
		writeError(w, http.StatusNotFound, "user not found")
		return
	}
	ch, err := crypto.NewChallenge()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	s.challenges.put(ch, 5*time.Minute)
	writeJSON(w, http.StatusOK, challengeResponse{Challenge: ch})
}

// handleAuthVerify — проверяет ed25519-подпись challenge и выдаёт сессионный токен.
func (s *Server) handleAuthVerify(w http.ResponseWriter, r *http.Request) {
	var req verifyRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if !s.challenges.consume(req.Challenge) {
		writeError(w, http.StatusUnauthorized, "invalid or expired challenge")
		return
	}
	username := strings.TrimSpace(req.Username)
	u, err := s.store.GetUserByUsername(r.Context(), username)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	sig, err := b64(req.Signature)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	if !crypto.VerifyEd25519(u.IdentityEd25519, []byte(req.Challenge), sig) {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}

	token, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	expires := time.Now().Add(s.cfg.TokenTTL)
	if err := s.store.PutToken(r.Context(), crypto.HashToken(token), u.ID, expires); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, verifyResponse{Token: token, ExpiresAt: expires.UTC().Format(time.RFC3339)})
}

// handleSendMessage — принимает уже зашифрованное сообщение и доставляет получателю.
func (s *Server) handleSendMessage(w http.ResponseWriter, r *http.Request) {
	senderID := r.Context().Value(ctxUserID).(string)

	var req sendMessageRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, s.cfg.MaxMessageBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if _, err := s.store.GetUserByID(r.Context(), req.RecipientID); err != nil {
		writeError(w, http.StatusNotFound, "recipient not found")
		return
	}
	ct, err := b64(req.Ciphertext)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid ciphertext")
		return
	}

	id, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	msg := &model.Message{
		ID:          id,
		SenderID:    senderID,
		RecipientID: req.RecipientID,
		Ciphertext:  ct,
		CreatedAt:   time.Now().UTC(),
	}
	if err := s.store.SaveMessage(r.Context(), msg); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	// Realtime-доставка, если получатель онлайн.
	s.hub.Push(req.RecipientID, ws.Event{
		Type: "message",
		Data: messageResponse{
			ID:          msg.ID,
			SenderID:    msg.SenderID,
			RecipientID: msg.RecipientID,
			Ciphertext:  req.Ciphertext,
			CreatedAt:   msg.CreatedAt.Format(time.RFC3339),
		},
	})

	writeJSON(w, http.StatusCreated, messageResponse{
		ID:          msg.ID,
		SenderID:    msg.SenderID,
		RecipientID: msg.RecipientID,
		Ciphertext:  req.Ciphertext,
		CreatedAt:   msg.CreatedAt.Format(time.RFC3339),
	})
}

// handleListMessages — отдаёт сообщения, адресованные текущему пользователю, начиная с since.
func (s *Server) handleListMessages(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	since := time.Unix(0, 0)
	if v := r.URL.Query().Get("since"); v != "" {
		if t, err := time.Parse(time.RFC3339, v); err == nil {
			since = t
		}
	}
	msgs, err := s.store.ListMessages(r.Context(), userID, since)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	out := make([]messageResponse, 0, len(msgs))
	for _, m := range msgs {
		out = append(out, messageResponse{
			ID:          m.ID,
			SenderID:    m.SenderID,
			RecipientID: m.RecipientID,
			ChatID:      m.ChatID,
			Ciphertext:  b64e(m.Ciphertext),
			CreatedAt:   m.CreatedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": out})
}

// handleWS — устанавливает WebSocket-соединение для realtime push.
// Аутентификация через ?token=... (токен не логируется в метаданных).
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	token := r.URL.Query().Get("token")
	if token == "" {
		writeError(w, http.StatusUnauthorized, "missing token")
		return
	}
	userID, err := s.store.GetUserIDByTokenHash(r.Context(), crypto.HashToken(token))
	if err != nil {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	upgrader := websocket.Upgrader{
		ReadBufferSize:  1024,
		WriteBufferSize: 4096,
		// Продакшн: ограничить Origin списком доверенных доменов.
		CheckOrigin: func(*http.Request) bool { return true },
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	client := ws.NewClient(s.hub, conn, userID)
	s.hub.Register(client)
	go client.WritePump()
	go client.ReadPump()
}

// ---------- утилиты ----------

func validUsername(s string) bool {
	if len(s) < 3 || len(s) > 32 {
		return false
	}
	for _, r := range s {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_') {
			return false
		}
	}
	return true
}

func b64(s string) ([]byte, error) {
	return base64.StdEncoding.DecodeString(s)
}

func b64e(b []byte) string {
	if len(b) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(b)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}
