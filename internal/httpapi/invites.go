package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net/http"
	"strings"
	"time"

	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

// Инвайт-коды (v0.19). Раньше новый человек мог войти, только если владелец
// вручную дописал его номер в AUTH_ALLOWED_PHONES и перезапустил сервер.
// Теперь владелец выдаёт код прямо из приложения.

const (
	inviteDefaultTTL  = 24 * time.Hour
	inviteMaxTTL      = 720 * time.Hour
	inviteMaxUses     = 20
	inviteMaxActive   = 50
	inviteMaxLabelLen = 64
	// Алфавит без похожих символов (0/O, 1/I): код диктуют голосом.
	inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	inviteCodeLen  = 10
)

type inviteView struct {
	ID        string  `json:"id"`
	Code      string  `json:"code,omitempty"`
	Label     string  `json:"label,omitempty"`
	MaxUses   int     `json:"max_uses"`
	Uses      int     `json:"uses"`
	Active    bool    `json:"active"`
	CreatedAt string  `json:"created_at"`
	ExpiresAt string  `json:"expires_at"`
	RevokedAt *string `json:"revoked_at,omitempty"`
}

// В проекте formatTime работает с *time.Time и возвращает *string,
// поэтому у инвайтов своё простое форматирование.
func inviteTime(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.UTC().Format(time.RFC3339)
}

func inviteToView(inv model.Invite, now time.Time, code string) inviteView {
	v := inviteView{
		ID:        inv.ID,
		Code:      code,
		Label:     inv.Label,
		MaxUses:   inv.MaxUses,
		Uses:      inv.Uses,
		Active:    inv.Active(now),
		CreatedAt: inviteTime(inv.CreatedAt),
		ExpiresAt: inviteTime(inv.ExpiresAt),
	}
	if inv.RevokedAt != nil {
		s := inviteTime(*inv.RevokedAt)
		v.RevokedAt = &s
	}
	return v
}

// normalizeInviteCode делает ввод терпимым к регистру, пробелам и дефисам.
func normalizeInviteCode(raw string) string {
	raw = strings.ToUpper(strings.TrimSpace(raw))
	raw = strings.Map(func(r rune) rune {
		if (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') {
			return r
		}
		return -1
	}, raw)
	raw = strings.TrimPrefix(raw, "UMBRA")
	if len(raw) > 64 {
		raw = raw[:64]
	}
	return raw
}

func inviteCodeHash(code string) string {
	return crypto.HashToken("invite:" + normalizeInviteCode(code))
}

func newInviteCode() (string, error) {
	b := make([]byte, 0, inviteCodeLen)
	for i := 0; i < inviteCodeLen; i++ {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(inviteAlphabet))))
		if err != nil {
			return "", err
		}
		b = append(b, inviteAlphabet[n.Int64()])
	}
	return "UMBRA-" + string(b), nil
}

// isOwner: владелец — аккаунт, чей номер перечислен в AUTH_ALLOWED_PHONES.
// Без списка допуска инвайты выключены: иначе любой пользователь смог бы
// раздавать доступ на закрытом сервере.
func (s *Server) isOwner(ctx context.Context, userID string) (bool, error) {
	if len(s.allowedPhones) == 0 {
		return false, nil
	}
	u, err := s.store.GetUserByID(ctx, userID)
	if err != nil {
		return false, err
	}
	return u.Phone != "" && s.allowedPhones[u.Phone], nil
}

func (s *Server) requireOwner(w http.ResponseWriter, r *http.Request) (string, bool) {
	userID, _ := r.Context().Value(ctxUserID).(string)
	if userID == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return "", false
	}
	ok, err := s.isOwner(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "service unavailable")
		return "", false
	}
	if !ok {
		writeError(w, http.StatusForbidden, "Инвайт-коды выдаёт только владелец Umbra")
		return "", false
	}
	return userID, true
}

// lookupInvite находит активный инвайт по сырому коду (используется в allowPhone).
func (s *Server) lookupInvite(ctx context.Context, code string) (*model.Invite, bool) {
	if normalizeInviteCode(code) == "" {
		return nil, false
	}
	inv, err := s.store.GetInviteByHash(ctx, inviteCodeHash(code), time.Now().UTC())
	if err != nil || inv == nil {
		return nil, false
	}
	return inv, true
}

// claimInvite фиксирует использование кода после успешной регистрации.
func (s *Server) claimInvite(ctx context.Context, code, phone, userID string) {
	if normalizeInviteCode(code) == "" {
		return
	}
	_ = s.store.ClaimInvite(ctx, inviteCodeHash(code), PhoneHash(phone), userID, time.Now().UTC())
}

type createInviteRequest struct {
	Label      string `json:"label"`
	MaxUses    int    `json:"max_uses"`
	TTLSeconds int    `json:"ttl_seconds"`
}

// handleCreateInvite — POST /v1/invites. Сам код показывается ровно один раз —
// в ответе на создание (в базе хранится только хеш).
func (s *Server) handleCreateInvite(w http.ResponseWriter, r *http.Request) {
	ownerID, ok := s.requireOwner(w, r)
	if !ok {
		return
	}
	var req createInviteRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	label := strings.TrimSpace(req.Label)
	if len([]rune(label)) > inviteMaxLabelLen {
		writeError(w, http.StatusBadRequest, "invalid label")
		return
	}
	maxUses := req.MaxUses
	if maxUses <= 0 {
		maxUses = 1
	}
	if maxUses > inviteMaxUses {
		writeError(w, http.StatusBadRequest, "invalid max_uses")
		return
	}
	ttl := inviteDefaultTTL
	if req.TTLSeconds > 0 {
		ttl = time.Duration(req.TTLSeconds) * time.Second
	}
	if ttl > inviteMaxTTL {
		writeError(w, http.StatusBadRequest, "invalid ttl_seconds")
		return
	}
	now := time.Now().UTC()
	existing, err := s.store.ListInvites(r.Context(), ownerID)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "service unavailable")
		return
	}
	active := 0
	for i := range existing {
		if existing[i].Active(now) {
			active++
		}
	}
	if active >= inviteMaxActive {
		writeError(w, http.StatusConflict, "Слишком много активных инвайт-кодов")
		return
	}
	id, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	code, err := newInviteCode()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	inv := &model.Invite{
		ID:        id,
		OwnerID:   ownerID,
		CodeHash:  inviteCodeHash(code),
		Label:     label,
		MaxUses:   maxUses,
		CreatedAt: now,
		ExpiresAt: now.Add(ttl),
	}
	if err := s.store.CreateInvite(r.Context(), inv); err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "invite already exists")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, http.StatusOK, inviteToView(*inv, now, code))
}

// handleListInvites — GET /v1/invites. Без самих кодов: их больше негде взять.
func (s *Server) handleListInvites(w http.ResponseWriter, r *http.Request) {
	ownerID, ok := s.requireOwner(w, r)
	if !ok {
		return
	}
	list, err := s.store.ListInvites(r.Context(), ownerID)
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "service unavailable")
		return
	}
	now := time.Now().UTC()
	out := make([]inviteView, 0, len(list))
	for _, inv := range list {
		out = append(out, inviteToView(inv, now, ""))
	}
	writeJSON(w, http.StatusOK, map[string]any{"invites": out})
}

// handleRevokeInvite — DELETE /v1/invites/{id}.
func (s *Server) handleRevokeInvite(w http.ResponseWriter, r *http.Request) {
	ownerID, ok := s.requireOwner(w, r)
	if !ok {
		return
	}
	id := strings.TrimSpace(r.PathValue("id"))
	if id == "" {
		writeError(w, http.StatusBadRequest, "invalid id")
		return
	}
	if err := s.store.RevokeInvite(r.Context(), ownerID, id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "invite not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "revoked", "id": id})
}

// handleInviteUses — GET /v1/invites/{id}/uses. Номера не раскрываются (только хеш).
func (s *Server) handleInviteUses(w http.ResponseWriter, r *http.Request) {
	ownerID, ok := s.requireOwner(w, r)
	if !ok {
		return
	}
	id := strings.TrimSpace(r.PathValue("id"))
	if id == "" {
		writeError(w, http.StatusBadRequest, "invalid id")
		return
	}
	uses, err := s.store.InviteUses(r.Context(), ownerID, id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "invite not found")
			return
		}
		writeError(w, http.StatusServiceUnavailable, "service unavailable")
		return
	}
	out := make([]map[string]string, 0, len(uses))
	for _, u := range uses {
		out = append(out, map[string]string{
			"phone_hash": u.PhoneHash,
			"user_id":    u.UserID,
			"used_at":    inviteTime(u.UsedAt),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"uses": out})
}
