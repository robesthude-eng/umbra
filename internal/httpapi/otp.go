package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

// Вход и регистрация по номеру телефона с кодом из Telegram (v0.4).
// Код приходит в личный чат бота (номер привязан к chat_id один раз),
// а не SMS: для семьи это бесплатно и достаточно.

const (
	otpTTL         = 5 * time.Minute
	otpMaxAttempts = 5
	otpCodeTTLText = "5 минут"
	dummyKeyBytes  = 32
)

// OTPSender доставляет код подтверждения. Реализация — Telegram-бот (main).
type OTPSender interface {
	// SendCode отправляет код пользователю; phone — нормализованный E.164.
	SendCode(ctx context.Context, phone string, tgChatID int64, code string) error
}

// otpStore — одноразовые коды подтверждения номера (в памяти сервера:
// экземпляр один, коды живут 5 минут; при рестарте истёкшие теряются — приемлемо).
type otpEntry struct {
	codeHash string
	expires  time.Time
	attempts int
}

type otpStore struct {
	mu sync.Mutex
	m  map[string]*otpEntry
}

func newOTPStore() *otpStore {
	return &otpStore{m: make(map[string]*otpEntry)}
}

func (o *otpStore) put(phone, code string, ttl time.Duration) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.m[phone] = &otpEntry{codeHash: crypto.HashToken(code), expires: time.Now().Add(ttl)}
}

// verify проверяет код и при успехе удаляет запись (одноразовость).
func (o *otpStore) verify(phone, code string) error {
	o.mu.Lock()
	defer o.mu.Unlock()
	e, ok := o.m[phone]
	if !ok || !e.expires.After(time.Now()) {
		delete(o.m, phone)
		return otpErrExpired
	}
	if e.attempts >= otpMaxAttempts {
		delete(o.m, phone)
		return otpErrTooMany
	}
	if crypto.HashToken(code) != e.codeHash {
		e.attempts++
		return otpErrInvalid
	}
	delete(o.m, phone)
	return nil
}

var (
	otpErrExpired = errors.New("код не найден или истёк")
	otpErrTooMany = errors.New("слишком много попыток")
	otpErrInvalid = errors.New("неверный код")
)

func randomOTPCode() (string, error) {
	buf := make([]byte, 3)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	n := int(buf[0])<<16 | int(buf[1])<<8 | int(buf[2])
	return fmt.Sprintf("%06d", n%1000000), nil
}

func normalizeCodeInput(raw string) string {
	raw = strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, strings.TrimSpace(raw))
	return raw
}

type requestCodeRequest struct {
	Phone string `json:"phone"`
}

// handleRequestCode — POST /v1/auth/request_code. Проверяет привязку номера к
// Telegram и отправляет код в чат бота. Аккаунт при этом не требуется:
// код — подтверждение номера и для входа, и для регистрации.
func (s *Server) handleRequestCode(w http.ResponseWriter, r *http.Request) {
	var req requestCodeRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	phone, err := NormalizePhone(req.Phone)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid phone")
		return
	}
	if s.otpSender == nil {
		writeError(w, http.StatusServiceUnavailable, "подтверждение по коду не настроено")
		return
	}
	// Универсальная схема: код с ЛЮБОГО номера уходит в чат владельца
	// (TELEGRAM_CHAT_ID) — он озвучивает код тому, кто регистрируется.
	chatID := s.cfg.TelegramChatID
	if chatID == 0 {
		writeError(w, http.StatusServiceUnavailable, "получатель кодов не настроен")
		return
	}
	code, err := randomOTPCode()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	s.otp.put(phone, code, otpTTL)
	if err := s.otpSender.SendCode(r.Context(), phone, chatID, code); err != nil {
		log.Printf("otp: не удалось отправить код для %s: %v", phone, err)
		writeError(w, http.StatusBadGateway, "не удалось отправить код")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "sent", "expires_in": otpCodeTTLText})
}

type verifyCodeRequest struct {
	Phone string `json:"phone"`
	Code  string `json:"code"`
}

type verifyCodeResponse struct {
	Token           string       `json:"token"`
	ExpiresAt       string       `json:"expires_at"`
	NewAccount      bool         `json:"new_account"`
	ProfileComplete bool         `json:"profile_complete"`
	Account         *accountView `json:"account"`
}

type accountView struct {
	ID          string `json:"id"`
	Username    string `json:"username"`
	Phone       string `json:"phone"`
	DisplayName string `json:"display_name"`
	LastName    string `json:"last_name,omitempty"`
}

// handleVerifyCode — POST /v1/auth/verify_code. При верном коде: если аккаунт
// с таким номером есть — вход; если нет — регистрация (создаётся облачный
// аккаунт без Signal-ключей, профиль заполняется дальше). Один аккаунт на номер.
func (s *Server) handleVerifyCode(w http.ResponseWriter, r *http.Request) {
	var req verifyCodeRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	phone, err := NormalizePhone(req.Phone)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid phone")
		return
	}
	code := normalizeCodeInput(req.Code)
	if len(code) != 6 {
		writeError(w, http.StatusBadRequest, "invalid code")
		return
	}
	if err := s.otp.verify(phone, code); err != nil {
		switch {
		case errors.Is(err, otpErrInvalid):
			writeError(w, http.StatusUnauthorized, "неверный код")
		case errors.Is(err, otpErrTooMany):
			writeError(w, http.StatusTooManyRequests, "слишком много попыток. Запросите новый код")
		default:
			writeError(w, http.StatusNotFound, "код не найден или истёк. Запросите новый")
		}
		return
	}

	u, err := s.store.GetUserByPhone(r.Context(), phone)
	newAccount := false
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusServiceUnavailable, "service unavailable")
			return
		}
		u, err = s.createCloudUser(r.Context(), phone)
		if err != nil {
			if errors.Is(err, store.ErrConflict) {
				// гонка: аккаунт уже создан (повторный запрос) — просто войти
				u, err = s.store.GetUserByPhone(r.Context(), phone)
				if err != nil {
					writeError(w, http.StatusInternalServerError, "internal error")
					return
				}
			} else {
				writeError(w, http.StatusInternalServerError, "internal error")
				return
			}
		} else {
			newAccount = true
		}
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
	writeJSON(w, http.StatusOK, verifyCodeResponse{
		Token:           token,
		ExpiresAt:       expires.UTC().Format(time.RFC3339),
		NewAccount:      newAccount,
		ProfileComplete: u.DisplayName != "",
		Account: &accountView{
			ID:          u.ID,
			Username:    u.Username,
			Phone:       u.Phone,
			DisplayName: u.DisplayName,
			LastName:    u.LastName,
		},
	})
}

// createCloudUser создаёт облачный аккаунт (T1): номер уникален, Signal-ключи —
// фиктивные (в этой модели не используются; реальные сообщения хранит сервер).
func (s *Server) createCloudUser(ctx context.Context, phone string) (*model.User, error) {
	id, err := crypto.NewToken()
	if err != nil {
		return nil, err
	}
	keys := func() []byte {
		b := make([]byte, dummyKeyBytes)
		_, _ = rand.Read(b)
		return b
	}
	u := &model.User{
		ID:              id,
		Username:        "u" + phone[1:],
		Phone:           phone,
		PhoneHash:       PhoneHash(phone),
		IdentityEd25519: keys(),
		IdentityX25519:  keys(),
		SignedPrekey:    keys(),
		SignedPrekeySig: keys(),
		CreatedAt:       time.Now().UTC(),
		KeyVersion:      1,
		RegistrationID:  1,
		SignedPrekeyID:  1,
	}
	return u, s.store.CreateUser(ctx, u)
}

type updateProfileRequest struct {
	Username string `json:"username"`
	Name     string `json:"name"`
	LastName string `json:"last_name"`
}

// handleUpdateProfile — POST /v1/account/profile. Завершение регистрации:
// @username (опционально) и отображаемое имя (обязательно для нового аккаунта).
func (s *Server) handleUpdateProfile(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	var req updateProfileRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	username := strings.TrimSpace(req.Username)
	name := strings.TrimSpace(req.Name)
	lastName := strings.TrimSpace(req.LastName)

	cur, err := s.store.GetUserByID(r.Context(), userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "account temporarily unavailable")
		}
		return
	}
	if name == "" {
		if cur.DisplayName == "" {
			writeError(w, http.StatusBadRequest, "Введите имя")
			return
		}
		name = cur.DisplayName
	} else if !validDisplayName(name) {
		writeError(w, http.StatusBadRequest, "invalid name")
		return
	}
	if lastName == "" {
		lastName = cur.LastName // необязательная фамилия сохраняется
	} else if len([]rune(lastName)) > 64 {
		writeError(w, http.StatusBadRequest, "invalid last_name")
		return
	}
	if username == "" {
		username = cur.Username
	} else if !validUsername(username) {
		writeError(w, http.StatusBadRequest, "invalid username")
		return
	}
	if err := s.store.UpdateAccountProfile(r.Context(), userID, username, name, lastName); err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "username already taken")
			return
		}
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": userID, "username": username, "display_name": name, "last_name": lastName})
}

type setAvatarRequest struct {
	MediaID string `json:"media_id"`
}

// handleSetAvatar — POST /v1/account/avatar. Привязывает загруженное медиа как
// аватар профиля (проверка: медиа принадлежит пользователю).
func (s *Server) handleSetAvatar(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	var req setAvatarRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	mediaID := strings.TrimSpace(req.MediaID)
	if mediaID == "" {
		writeError(w, http.StatusBadRequest, "invalid media_id")
		return
	}
	m, err := s.store.GetMedia(r.Context(), mediaID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "media not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "service unavailable")
		}
		return
	}
	if m.OwnerID != userID {
		writeError(w, http.StatusForbidden, "media belongs to another account")
		return
	}
	if err := s.store.SetAvatar(r.Context(), userID, mediaID); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok", "avatar_media_id": mediaID})
}
