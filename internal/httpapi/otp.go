package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

// Вход и регистрация по номеру телефона с кодом из Telegram (v0.4).
// Код получает владелец сервера и передаёт пользователю; владение SIM не проверяется.

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

func randomOTPCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1000000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}
func cleanDeviceName(raw string) string {
	raw = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return -1
		}
		return r
	}, raw)
	r := []rune(strings.TrimSpace(raw))
	if len(r) > 80 {
		r = r[:80]
	}
	if len(r) == 0 {
		return "Неизвестное устройство"
	}
	return string(r)
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
	DeviceName string `json:"device_name"`
	Phone      string `json:"phone"`
}

// handleRequestCode checks the phone policy and requests owner approval.
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
	if !s.allowPhone(w, r, phone) {
		return
	}
	s.sendCode(w, r, phone, "login", "", cleanDeviceName(req.DeviceName), req.DeviceName == "")
}

// Sensitive codes must be explicitly labelled by their delivery adapter.
type SecurityCodeSender interface {
	SendSecurityCode(context.Context, string, int64, string, string, string) error
}

func (s *Server) sendCode(w http.ResponseWriter, r *http.Request, phone, purpose, binding, device string, legacy bool) {
	if s.otpSender == nil || s.cfg.TelegramChatID == 0 {
		writeError(w, 503, "доставка кодов не настроена")
		return
	}
	secure, canLabel := s.otpSender.(SecurityCodeSender)
	if purpose != "login" && !canLabel {
		writeError(w, 503, "подтверждение удаления не настроено")
		return
	}
	code, err := randomOTPCode()
	if err != nil {
		writeError(w, 500, "internal error")
		return
	}
	id, retry, err := s.otp.issue(phone, purpose, binding, device, code, legacy)
	if err != nil {
		if retry > 0 {
			seconds := int((retry + time.Second - 1) / time.Second)
			w.Header().Set("Retry-After", strconv.Itoa(seconds))
			writeError(w, 429, fmt.Sprintf("Слишком много запросов. Повторите через %d с.", seconds))
		} else {
			writeError(w, 500, "internal error")
		}
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	if canLabel {
		err = secure.SendSecurityCode(ctx, phone, s.cfg.TelegramChatID, code, purpose, device)
	} else {
		err = s.otpSender.SendCode(ctx, phone, s.cfg.TelegramChatID, code)
	}
	if err != nil {
		s.otp.invalidate(phone, purpose, id)
		log.Print("otp: delivery failed")
		writeError(w, 502, "Не удалось отправить код. Повторите позже.")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, 200, map[string]any{"status": "sent", "expires_in": otpCodeTTLText, "request_id": id, "retry_after": 60})
}
func (s *Server) allowPhone(w http.ResponseWriter, r *http.Request, phone string) bool {
	if s.phonePolicyErr != nil {
		writeError(w, 503, "service unavailable")
		return false
	}
	allowed := s.allowedPhones[phone]
	if len(s.allowedPhones) == 0 {
		_, err := s.store.GetUserByPhone(r.Context(), phone)
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			writeError(w, 503, "service unavailable")
			return false
		}
		allowed = err == nil
	}
	if !allowed {
		writeError(w, 403, "Вход для этого номера не разрешён владельцем Umbra")
		return false
	}
	return true
}

type verifyCodeRequest struct {
	RequestID string `json:"request_id"`
	Phone     string `json:"phone"`
	Code      string `json:"code"`
}

type verifyCodeResponse struct {
	Token           string       `json:"token"`
	ExpiresAt       string       `json:"expires_at"`
	NewAccount      bool         `json:"new_account"`
	ProfileComplete bool         `json:"profile_complete"`
	Account         *accountView `json:"account"`
}

type accountView struct {
	ID            string `json:"id"`
	Username      string `json:"username"`
	Phone         string `json:"phone"`
	DisplayName   string `json:"display_name"`
	LastName      string `json:"last_name,omitempty"`
	AvatarMediaID string `json:"avatar_media_id,omitempty"`
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
	if !s.allowPhone(w, r, phone) {
		return
	}
	device, err := s.otp.verify(phone, "login", "", req.RequestID, code)
	if err != nil {
		writeOTPError(w, err)
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
	now := time.Now().UTC()
	sessionID, err := crypto.NewToken()
	if err != nil {
		writeError(w, 500, "internal error")
		return
	}
	session := &model.AuthSession{ID: sessionID, UserID: u.ID, TokenHash: crypto.HashToken(token), DeviceName: device, CreatedAt: now, LastSeenAt: now, ExpiresAt: now.Add(s.cfg.TokenTTL)}
	if err := s.store.CreateSession(r.Context(), session); err != nil {
		writeError(w, 500, "internal error")
		return
	}
	expires := session.ExpiresAt
	if !newAccount {
		s.notifyNewSession(u.ID, session.ID, device, now)
	}
	w.Header().Set("Cache-Control", "no-store")
	avatar, _ := s.store.GetAvatar(r.Context(), u.ID)
	writeJSON(w, http.StatusOK, verifyCodeResponse{
		Token:           token,
		ExpiresAt:       expires.UTC().Format(time.RFC3339),
		NewAccount:      newAccount,
		ProfileComplete: u.DisplayName != "",
		Account: &accountView{
			ID:            u.ID,
			Username:      u.Username,
			Phone:         u.Phone,
			DisplayName:   u.DisplayName,
			LastName:      u.LastName,
			AvatarMediaID: avatar,
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
	Username string  `json:"username"`
	Name     string  `json:"name"`
	LastName *string `json:"last_name"`
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

	cur, err := s.store.GetUserByID(r.Context(), userID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
		} else {
			writeError(w, http.StatusServiceUnavailable, "account temporarily unavailable")
		}
		return
	}
	lastName := cur.LastName
	if req.LastName != nil {
		lastName = strings.TrimSpace(*req.LastName)
	}
	// Имя обязательно всегда (правило пользователя; соответствует UI, которое не
	// позволяет очистить имя). Клиент, обновляющий только username/last_name,
	// присылает текущее имя; пустое имя отклоняется.
	if name == "" {
		writeError(w, http.StatusBadRequest, "Введите имя")
		return
	}
	if !validDisplayName(name) {
		writeError(w, http.StatusBadRequest, "invalid name")
		return
	}
	if len([]rune(lastName)) > 64 {
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
