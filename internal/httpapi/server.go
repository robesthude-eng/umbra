// Package httpapi — HTTP/WebSocket API сервера.
package httpapi

import (
	"context"
	"log"
	"net/http"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/crypto"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

type ctxKey string

const ctxUserID ctxKey = "user_id"

// Server связывает конфигурацию, хранилище и WebSocket-хаб.
type Server struct {
	cfg        *config.Config
	store      store.Store
	blobs      blobstore.BlobStore
	hub        *ws.Hub
	challenges *challengeStore
}

// NewServer сохраняет прежний контракт; без BlobStore медиа возвращает 503.
func NewServer(cfg *config.Config, st store.Store, hub *ws.Hub) *http.Server {
	return NewServerWithBlobStore(cfg, st, hub, nil)
}

// NewServerWithBlobStore включает медиа; вызывающий код закрывает оба хранилища.
func NewServerWithBlobStore(cfg *config.Config, st store.Store, hub *ws.Hub, blobs blobstore.BlobStore) *http.Server {
	s := &Server{cfg: cfg, store: st, blobs: blobs, hub: hub, challenges: newChallengeStore()}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("POST /v1/register", s.handleRegister)
	mux.HandleFunc("GET /v1/users/{username}/prekeys", s.handlePrekeys)
	mux.HandleFunc("POST /v1/auth/challenge", s.handleAuthChallenge)
	mux.HandleFunc("POST /v1/auth/verify", s.handleAuthVerify)
	mux.Handle("POST /v1/messages", s.requireAuth(http.HandlerFunc(s.handleSendMessage)))
	mux.Handle("GET /v1/messages", s.requireAuth(http.HandlerFunc(s.handleListMessages)))
	mux.Handle("POST /v1/media", s.requireAuth(http.HandlerFunc(s.handleUploadMedia)))
	mux.Handle("GET /v1/media/{id}", s.requireAuth(http.HandlerFunc(s.handleDownloadMedia)))
	mux.HandleFunc("GET /v1/ws", s.handleWS)

	return &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           logMiddleware(mux),
		ReadHeaderTimeout: 10 * time.Second,
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
		userID, err := s.store.GetUserIDByTokenHash(r.Context(), crypto.HashToken(token))
		if err != nil {
			writeError(w, http.StatusUnauthorized, "unauthorized")
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
		log.Printf("%s %s %s (%s)", r.Method, r.URL.Path, r.RemoteAddr, time.Since(start))
	})
}
