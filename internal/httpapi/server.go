// Package httpapi — HTTP/WebSocket API сервера.
package httpapi

import (
	"context"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/cloudcrypto"
	"umbra/server/internal/config"
	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

type ctxKey string

const ctxUserID ctxKey = "user_id"

// Server связывает конфигурацию, хранилище и WebSocket-хаб.
type Server struct {
	allowedPhones  map[string]bool
	phonePolicyErr error
	storage        *cloudcrypto.Keyring
	cfg            *config.Config
	store          store.Store
	blobs          blobstore.BlobStore
	hub            *ws.Hub
	challenges     *challengeStore
	typing         *typingStore
	uploads        chan struct{}
	otp            *otpStore
	otpSender      OTPSender
	pusher         pushSender
	handler        http.Handler

	// callLeavers помнит, кто уже вышел из группового звонка (см. calls.go).
	callLeavers *callLeaverStore
}

// NewServer сохраняет прежний контракт; без BlobStore медиа возвращает 503.
func NewServer(cfg *config.Config, st store.Store, hub *ws.Hub) *http.Server {
	return NewServerWithBlobStore(cfg, st, hub, nil)
}

// NewServerWithBlobStore включает медиа; вызывающий код закрывает оба хранилища.
func NewServerWithBlobStore(cfg *config.Config, st store.Store, hub *ws.Hub, blobs blobstore.BlobStore, keys ...*cloudcrypto.Keyring) *http.Server {
	return NewServerForMain(cfg, st, hub, blobs, nil, keys...)
}

// NewServerForMain собирает сервер с OTP-доставкой кодов (Telegram) и готов к запуску.
func NewServerForMain(cfg *config.Config, st store.Store, hub *ws.Hub, blobs blobstore.BlobStore, otpSender OTPSender, keys ...*cloudcrypto.Keyring) *http.Server {
	s := &Server{cfg: cfg, store: st, blobs: blobs, hub: hub, challenges: newChallengeStore(), typing: newTypingStore(), uploads: make(chan struct{}, 8), otp: newOTPStore(), otpSender: otpSender, pusher: newPusher(cfg), callLeavers: newCallLeaverStore()}

	s.allowedPhones, s.phonePolicyErr = config.ParseAllowedPhones(cfg.AllowedPhones)
	if len(keys) > 0 {
		s.storage = keys[0]
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("POST /v1/register", s.handleRegister)
	mux.HandleFunc("GET /v1/users/{username}/prekeys", s.handlePrekeys)
	mux.HandleFunc("POST /v1/auth/challenge", s.handleAuthChallenge)
	mux.HandleFunc("POST /v1/auth/verify", s.handleAuthVerify)
	// Вход/регистрация по номеру с кодом из Telegram (v0.4).
	mux.HandleFunc("POST /v1/auth/request_code", s.handleRequestCode)
	mux.HandleFunc("POST /v1/auth/verify_code", s.handleVerifyCode)
	mux.Handle("POST /v1/auth/logout", s.requireAuth(http.HandlerFunc(s.handleLogout)))
	mux.Handle("POST /v1/auth/refresh", s.requireAuth(http.HandlerFunc(s.handleRefreshSession)))
	mux.Handle("PUT /v1/account/keys", s.requireAuth(http.HandlerFunc(s.handleUpdateKeys)))
	mux.Handle("POST /v1/messages", s.requireAuth(http.HandlerFunc(s.handleSendMessage)))
	mux.Handle("GET /v1/messages", s.requireAuth(http.HandlerFunc(s.handleListMessages)))
	mux.Handle("POST /v1/media", s.requireAuth(http.HandlerFunc(s.handleUploadMedia)))
	mux.Handle("GET /v1/media/{id}", s.requireAuth(http.HandlerFunc(s.handleDownloadMedia)))
	// Группы, каналы, контакты.
	mux.Handle("POST /v1/groups", s.requireAuth(s.handleCreateChat(model.ChatGroup)))
	mux.Handle("POST /v1/channels", s.requireAuth(s.handleCreateChat(model.ChatChannel)))
	mux.Handle("GET /v1/chats", s.requireAuth(http.HandlerFunc(s.handleListChats)))
	mux.Handle("POST /v1/chats/{id}/members", s.requireAuth(http.HandlerFunc(s.handleAddMember)))
	mux.Handle("DELETE /v1/chats/{id}/members/{user_id}", s.requireAuth(http.HandlerFunc(s.handleRemoveMember)))
	mux.Handle("GET /v1/chats/{id}/members", s.requireAuth(http.HandlerFunc(s.handleListMembers)))
	mux.Handle("POST /v1/chats/{id}/messages", s.requireAuth(http.HandlerFunc(s.handleSendChatMessage)))
	mux.Handle("POST /v1/contacts", s.requireAuth(http.HandlerFunc(s.handleAddContact)))
	mux.Handle("GET /v1/contacts", s.requireAuth(http.HandlerFunc(s.handleListContacts)))
	mux.Handle("POST /v1/contacts/discover", s.requireAuth(http.HandlerFunc(s.handleDiscoverContacts)))
	mux.Handle("POST /v1/chats/{id}/typing", s.requireAuth(http.HandlerFunc(s.handleMarkTyping)))
	mux.Handle("GET /v1/chats/{id}/typing", s.requireAuth(http.HandlerFunc(s.handleListTyping)))
	// Звонки (сигналинг; медиа peer-to-peer).
	mux.Handle("POST /v1/calls", s.requireAuth(http.HandlerFunc(s.handleInitiateCall)))
	mux.Handle("POST /v1/calls/{id}/signal", s.requireAuth(http.HandlerFunc(s.handleCallSignal)))
	mux.Handle("POST /v1/calls/{id}/status", s.requireAuth(http.HandlerFunc(s.handleUpdateCallStatus)))
	mux.Handle("GET /v1/calls", s.requireAuth(http.HandlerFunc(s.handleListCalls)))
	mux.Handle("GET /v1/turn", s.requireAuth(http.HandlerFunc(s.handleIceConfig)))
	// Push-уведомления: регистрация и отвязка токена устройства.
	mux.Handle("POST /v1/push/devices", s.requireAuth(http.HandlerFunc(s.handleRegisterPushDevice)))
	mux.Handle("DELETE /v1/push/devices", s.requireAuth(http.HandlerFunc(s.handleDeletePushDevice)))
	// Аккаунт: получение и полное удаление («сжечь»).
	mux.Handle("GET /v1/account", s.requireAuth(http.HandlerFunc(s.handleGetAccount)))
	mux.Handle("POST /v1/account/security/code", s.requireAuth(http.HandlerFunc(s.handleSecurityCode)))
	mux.Handle("GET /v1/account/sessions", s.requireAuth(http.HandlerFunc(s.handleListSessions)))
	mux.Handle("DELETE /v1/account/sessions/{id}", s.requireAuth(http.HandlerFunc(s.handleRevokeSession)))
	mux.Handle("POST /v1/account/sessions/revoke_others", s.requireAuth(http.HandlerFunc(s.handleRevokeOtherSessions)))
	mux.Handle("POST /v1/account/burn", s.requireAuth(http.HandlerFunc(s.handleBurnAccount)))
	mux.Handle("POST /v1/account/transfer", s.requireAuth(http.HandlerFunc(s.handleCreateTransfer)))
	mux.HandleFunc("POST /v1/account/transfer/claim", s.handleClaimTransfer)
	// Профиль (имя, @username) и аватар — завершение регистрации/редактирование.
	mux.Handle("POST /v1/account/profile", s.requireAuth(http.HandlerFunc(s.handleUpdateProfile)))
	mux.Handle("POST /v1/account/avatar", s.requireAuth(http.HandlerFunc(s.handleSetAvatar)))
	// Публичная карточка пользователя (имя/аватар для диалогов и групп).
	mux.Handle("GET /v1/users/{user_id}", s.requireAuth(http.HandlerFunc(s.handleGetUser)))
	mux.Handle("GET /v1/by-username/{username}", s.requireAuth(http.HandlerFunc(s.handleGetUserByUsername)))
	mux.HandleFunc("GET /v1/ws", s.handleWS)

	// Load/Validate rejects invalid configuration in production. A constructor
	// used directly by tests also fails closed: invalid values trust no proxies.
	trustedProxies, err := config.ParseTrustedProxies(cfg.TrustedProxies)
	if err != nil {
		log.Printf("proxy configuration ignored: %v", err)
	}
	s.handler = logMiddleware(newRequestLimiter(trustedProxies...).wrap(mux))
	return &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           s,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       5 * time.Minute,
		WriteTimeout:      5 * time.Minute,
		IdleTimeout:       60 * time.Second,
	}
}

// requireAuth проверяет Bearer-токен и кладёт userID в контекст запроса.
func (s *Server) requireAuth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		token, ok := bearerToken(r)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		userID, err := s.sessionUser(r.Context(), crypto.HashToken(token))
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				writeError(w, http.StatusUnauthorized, "unauthorized")
			} else {
				writeError(w, http.StatusServiceUnavailable, "authentication temporarily unavailable")
			}
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxUserID, userID)))
	})
}

func bearerToken(r *http.Request) (string, bool) {
	const prefix = "Bearer "
	h := r.Header.Get("Authorization")
	if len(h) > len(prefix) && h[:len(prefix)] == prefix {
		return h[len(prefix):], true
	}
	return "", false
}

func logMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		if strings.HasSuffix(r.URL.Path, "/typing") {
			return
		}
		log.Printf("%s %s %s (%s)", r.Method, r.URL.Path, r.RemoteAddr, time.Since(start))
	})
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) { s.handler.ServeHTTP(w, r) }
func (s *Server) sessionUser(ctx context.Context, hash string) (string, error) {
	id, err := s.store.GetUserIDByTokenHash(ctx, hash)
	if err != nil {
		return "", err
	}
	if s.phonePolicyErr != nil {
		return "", s.phonePolicyErr
	}
	if len(s.allowedPhones) > 0 {
		u, err := s.store.GetUserByID(ctx, id)
		if err != nil {
			return "", err
		}
		if !(u.Phone == "" && s.cfg.AllowLegacyAuth) && !s.allowedPhones[u.Phone] {
			return "", store.ErrNotFound
		}
	}
	return id, nil
}
