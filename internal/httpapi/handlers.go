package httpapi

import (
	"context"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
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
	// Phone — номер телефона (нормализуется к E.164); Name — отображаемое имя.
	// Если задан phone, username можно не передавать: сервер сгенерирует
	// служебный идентификатор вида u<digits>.
	Phone string `json:"phone"`
	Name  string `json:"name"`
	IdentityEd25519 string   `json:"identity_ed25519"` // base64
	IdentityX25519  string   `json:"identity_x25519"`  // base64
	SignedPrekey    string   `json:"signed_prekey"`    // base64
	SignedPrekeySig string   `json:"signed_prekey_signature"`
	OneTimePrekeys  []string `json:"one_time_prekeys"` // base64
	KeyVersion      int      `json:"key_version"`
	RegistrationID  int      `json:"registration_id"`
	SignedPrekeyID  int      `json:"signed_prekey_id"`
	OneTimePrekeyIDs []int    `json:"one_time_prekey_ids"`
	KeyBundleID      string   `json:"key_bundle_id"`
}

type prekeysResponse struct {
	ID              string `json:"id"`
	Username        string `json:"username"`
	IdentityEd25519 string `json:"identity_ed25519"`
	IdentityX25519  string `json:"identity_x25519"`
	SignedPrekey    string `json:"signed_prekey"`
	SignedPrekeySig string `json:"signed_prekey_signature"`
	OneTimePrekey   string `json:"one_time_prekey"`
	KeyVersion      int    `json:"key_version"`
	RegistrationID  int    `json:"registration_id"`
	SignedPrekeyID  int    `json:"signed_prekey_id"`
	OneTimePrekeyID int    `json:"one_time_prekey_id"`
	DeviceID       int    `json:"device_id"`
}

type challengeRequest struct {
	Username string `json:"username"`
	Phone    string `json:"phone"`
}

type challengeResponse struct {
	Challenge string `json:"challenge"`
}

type verifyRequest struct {
	Username  string `json:"username"`
	Phone     string `json:"phone"`
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
	// ExpiresIn — секунды до самоуничтожения (секретный чат). 0 = без таймера.
	ExpiresIn int64 `json:"expires_in"`
	ClientID string `json:"client_message_id"`
}

type messageResponse struct {
	ID          string  `json:"id"`
	SenderID    string  `json:"sender_id"`
	RecipientID string  `json:"recipient_id"`
	ChatID      string  `json:"chat_id"`
	Ciphertext  string  `json:"ciphertext"`
	CreatedAt   string  `json:"created_at"`
	ExpiresAt   *string `json:"expires_at"`
	ClientID    string  `json:"client_message_id,omitempty"`
}

// challengeStore — in-memory хранилище одноразовых challenge (nonce) с TTL.
// Ограничивает время жизни nonce и не даёт повторно использовать подпись.
type challengeStore struct {
	mu   sync.Mutex
	data map[string]challengeEntry
}

type challengeEntry struct { username string; expires time.Time }

func newChallengeStore() *challengeStore { return &challengeStore{data: make(map[string]challengeEntry)} }

func (c *challengeStore) put(challenge string, ttl time.Duration, username ...string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	for key, value := range c.data { if !value.expires.After(now) { delete(c.data, key) } }
	if len(c.data) >= 4096 { return false }
	name := ""
	if len(username) != 0 { name = username[0] }
	c.data[challenge] = challengeEntry{name, now.Add(ttl)}
	return true
}

func (c *challengeStore) consume(challenge string, username ...string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	exp, ok := c.data[challenge]
	if !ok {
		return false
	}
	if len(username) != 0 && exp.username != username[0] { return false }
	delete(c.data, challenge)
	return time.Now().Before(exp.expires)
}

// ---------- хэндлеры ----------

func (s *Server) handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// handleRegister — регистрация пользователя по username + открытые ключи.
// НИКАКИХ паролей и номеров телефонов — только публичные ключи.
func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.AllowLegacyAuth {
		writeError(w, http.StatusGone, "key registration is disabled; use phone verification")
		return
	}
	var req registerRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	username := strings.TrimSpace(req.Username)
	displayName := strings.TrimSpace(req.Name)
	if strings.TrimSpace(req.Phone) != "" {
		writeError(w, http.StatusForbidden, "phone registration requires verification code")
		return
	}
	if !validUsername(username) {
		writeError(w, http.StatusBadRequest, "invalid username")
		return
	}

	idKey, err := b64(req.IdentityEd25519)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid identity_ed25519")
		return
	}
	idKey, err = crypto.NormalizeEd25519(idKey)
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
		DisplayName:     displayName,
		IdentityEd25519: idKey,
		IdentityX25519:  ix,
		SignedPrekey:    spk,
		SignedPrekeySig: spsig,
		OneTimePrekeys:  otks,
		CreatedAt:       time.Now().UTC(),
		KeyVersion: req.KeyVersion, RegistrationID: req.RegistrationID, SignedPrekeyID: req.SignedPrekeyID,
		KeyBundleID: req.KeyBundleID,
	}
	if err := prepareKeyMaterial(u, req.OneTimePrekeyIDs); err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err := s.store.CreateUser(r.Context(), u); err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "username already taken")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{
		"id": userID, "username": username, "phone": "", "display_name": displayName,
	})
}

// handlePrekeys — отдаёт публичный ключевой материал пользователя для X3DH.
func (s *Server) handlePrekeys(w http.ResponseWriter, r *http.Request) {
	username := r.PathValue("username")
	u, otk, err := s.store.TakePrekeyBundle(r.Context(), username)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) { writeError(w, http.StatusNotFound, "user not found") } else { writeError(w, http.StatusInternalServerError, "internal error") }
		return
	}
	otkID := 0
	if u.KeyVersion == 2 && len(otk) != 0 {
		if len(otk) != 37 { writeError(w, http.StatusInternalServerError, "invalid stored prekey"); return }
		otkID = int(binary.BigEndian.Uint32(otk[:4]))
		otk = otk[4:]
	}
	writeJSON(w, http.StatusOK, prekeysResponse{
		ID:              u.ID,
		Username:        u.Username,
		IdentityEd25519: b64e(u.IdentityEd25519),
		IdentityX25519:  b64e(u.IdentityX25519),
		SignedPrekey:    b64e(u.SignedPrekey),
		SignedPrekeySig: b64e(u.SignedPrekeySig),
		OneTimePrekey:   b64e(otk),
		KeyVersion: u.KeyVersion, RegistrationID: u.RegistrationID, SignedPrekeyID: u.SignedPrekeyID,
		OneTimePrekeyID: otkID, DeviceID: 1,
	})
}

// handleAuthChallenge — выдаёт nonce для подписи (challenge-response).
func (s *Server) handleAuthChallenge(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.AllowLegacyAuth {
		writeError(w, http.StatusGone, "key authentication is disabled; use phone verification")
		return
	}
	var req challengeRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	u, err := s.findUserByLogin(r, req.Username, req.Phone)
	if err != nil || u.Phone != "" {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}
	ch, err := crypto.NewChallenge()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	if !s.challenges.put(ch, 5*time.Minute, u.Username) {
		writeError(w, http.StatusTooManyRequests, "too many challenges")
		return
	}
	writeJSON(w, http.StatusOK, challengeResponse{Challenge: ch})
}

// findUserByLogin находит пользователя по номеру телефона (приоритет) или
// по username (legacy-вход старых клиентов).
func (s *Server) findUserByLogin(r *http.Request, username, phone string) (*model.User, error) {
	if raw := strings.TrimSpace(phone); raw != "" {
		normalized, err := NormalizePhone(raw)
		if err != nil {
			return nil, err
		}
		return s.store.GetUserByPhone(r.Context(), normalized)
	}
	return s.store.GetUserByUsername(r.Context(), strings.TrimSpace(username))
}

// handleAuthVerify — проверяет ed25519-подпись challenge и выдаёт сессионный токен.
func (s *Server) handleAuthVerify(w http.ResponseWriter, r *http.Request) {
	if !s.cfg.AllowLegacyAuth {
		writeError(w, http.StatusGone, "key authentication is disabled; use phone verification")
		return
	}
	var req verifyRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	u, err := s.findUserByLogin(r, req.Username, req.Phone)
	if err != nil || u.Phone != "" {
		writeError(w, http.StatusUnauthorized, "invalid credentials")
		return
	}
	if !s.challenges.consume(req.Challenge, u.Username) {
		writeError(w, http.StatusUnauthorized, "invalid or expired challenge")
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
	if err != nil || len(ct) == 0 || !validMessageOptions(req.ClientID, req.ExpiresIn) {
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
		ClientID: req.ClientID, ExpiresIn: req.ExpiresIn,
	}
	if req.ExpiresIn > 0 {
		exp := msg.CreatedAt.Add(time.Duration(req.ExpiresIn) * time.Second)
		msg.ExpiresAt = &exp
	}
	if err := s.store.SaveMessage(r.Context(), msg); err != nil {
		if errors.Is(err, store.ErrConflict) { writeError(w, http.StatusConflict, "client_message_id reused with different content"); return }
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	resp := messageResponse{
		ID:          msg.ID,
		SenderID:    msg.SenderID,
		RecipientID: msg.RecipientID,
		Ciphertext:  req.Ciphertext,
		CreatedAt:   msg.CreatedAt.Format(time.RFC3339Nano),
		ExpiresAt:   formatTime(msg.ExpiresAt),
		ClientID: msg.ClientID,
	}

	// Realtime-доставка, если получатель онлайн.
	if msg.ExpiresAt == nil || msg.ExpiresAt.After(time.Now()) {
		s.hub.Push(req.RecipientID, ws.Event{Type: "message", Data: resp})
	}

	writeJSON(w, http.StatusCreated, resp)
}

// handleListMessages — отдаёт сообщения, адресованные текущему пользователю, начиная с since.
func (s *Server) handleListMessages(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	since := time.Unix(0, 0)
	if v := r.URL.Query().Get("since"); v != "" {
		if t, err := time.Parse(time.RFC3339, v); err == nil {
			since = t
		} else {
			writeError(w, http.StatusBadRequest, "invalid since"); return
		}
	}
	limit := 200
	if v := r.URL.Query().Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 500 { writeError(w, http.StatusBadRequest, "invalid limit"); return }
		limit = n
	}
	msgs, err := s.store.ListMessagesPage(r.Context(), userID, since, r.URL.Query().Get("after_id"), limit)
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
			CreatedAt:   m.CreatedAt.Format(time.RFC3339Nano),
			ExpiresAt:   formatTime(m.ExpiresAt),
			ClientID:    m.ClientID,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"messages": out})
}

// handleWS — устанавливает WebSocket-соединение для realtime push.
// Bearer-заголовок; query token поддерживается для старых клиентов.
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	token, _ := bearerToken(r)
	if token == "" { token = r.URL.Query().Get("token") } // Совместимость со старым клиентом.
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
		CheckOrigin: func(req *http.Request) bool {
			return req.Header.Get("Origin") == "" || req.Header.Get("Origin") == "https://"+req.Host
		},
	}
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	client := ws.NewClient(s.hub, conn, userID)
	client.SetAuthorization(func() bool {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		id, err := s.store.GetUserIDByTokenHash(ctx, crypto.HashToken(token))
		return err == nil && id == userID
	})
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

func formatTime(t *time.Time) *string {
	if t == nil {
		return nil
	}
	s := t.Format(time.RFC3339Nano)
	return &s
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
