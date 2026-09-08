package httpapi

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"umbra/server/internal/crypto"
	"umbra/server/internal/store"
)

// Перенос аккаунта между устройствами (v0.4).
// POST /v1/account/transfer  (auth):   {vault} -> {code, expires_at}
// POST /v1/account/transfer/claim      {code}  -> {vault}
//
// vault — непрозрачный blob, зашифрованный клиентом (identity + история).
// Код одноразовый, живёт 10 минут; на сервере хранится только его SHA-256.

const (
	transferTTL        = 10 * time.Minute
	maxTransferVault   = 16 << 20 // 16 MiB: лимит размера зашифрованного бэкапа
	transferCodeSymbol = "ABCDEFGHJKMNPQRSTVWXYZ23456789"
	transferCodeLen    = 12
)

type createTransferRequest struct {
	// Vault — base64 зашифрованного бэкапа аккаунта.
	Vault string `json:"vault"`
}

type createTransferResponse struct {
	Code      string `json:"code"`
	ExpiresAt string `json:"expires_at"`
}

type claimTransferRequest struct {
	Code string `json:"code"`
}

type claimTransferResponse struct {
	Vault string `json:"vault"`
}

func newTransferCode() (string, error) {
	buf := make([]byte, transferCodeLen)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	code := make([]byte, 0, transferCodeLen)
	for _, b := range buf {
		code = append(code, transferCodeSymbol[int(b)%len(transferCodeSymbol)])
	}
	// Формат для удобства ввода: XXXX-XXXX-XXXX.
	return string(code[0:4]) + "-" + string(code[4:8]) + "-" + string(code[8:12]), nil
}

// normalizeTransferCode приводит код клиента к каноническому виду: без
// разделителей, в верхнем регистре. Возвращает пустую строку, если код
// не похож на код переноса (защита от явного мусора).
func normalizeTransferCode(raw string) string {
	upper := strings.ToUpper(raw)
	code := strings.Map(func(r rune) rune {
		switch {
		case r >= 'A' && r <= 'Z':
			return r
		case r >= '0' && r <= '9':
			return r
		default:
			return -1 // разделители ('-', пробелы и пр.) отбрасываются
		}
	}, upper)
	if len(code) != transferCodeLen {
		return ""
	}
	for _, c := range code {
		if !strings.ContainsRune(transferCodeSymbol, c) {
			return ""
		}
	}
	return code
}

// handleCreateTransfer — POST /v1/account/transfer. Создаёт одноразовый код
// переноса и сохраняет под ним зашифрованный бэкап. Прежний неиспользованный
// код пользователя отзывается.
func (s *Server) handleCreateTransfer(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)

	var req createTransferRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxTransferVault+1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	vault, err := b64(req.Vault)
	if err != nil || len(vault) == 0 {
		writeError(w, http.StatusBadRequest, "invalid vault")
		return
	}
	if len(vault) > maxTransferVault {
		writeError(w, http.StatusRequestEntityTooLarge, "vault too large")
		return
	}

	code, err := newTransferCode()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	// Храним хэш канонического кода (без разделителей): при claim клиент шлёт
	// код в любом регистре/формате, и normalizeTransferCode приводит к тому же виду.
	canonical := normalizeTransferCode(code)
	if err := s.store.PutAccountTransfer(r.Context(), userID, crypto.HashToken(canonical), vault, time.Now().Add(transferTTL)); err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, createTransferResponse{
		Code:      code,
		ExpiresAt: time.Now().Add(transferTTL).UTC().Format(time.RFC3339),
	})
}

// handleClaimTransfer — POST /v1/account/transfer/claim. По одноразовому коду
// возвращает зашифрованный бэкап и гасит код. Аутентификация не требуется:
// код сам по себе — мандат на получение бэкапа (короткий TTL, лимит попыток).
func (s *Server) handleClaimTransfer(w http.ResponseWriter, r *http.Request) {
	var req claimTransferRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	code := normalizeTransferCode(req.Code)
	if code == "" {
		writeError(w, http.StatusBadRequest, "invalid code")
		return
	}
	_, vault, err := s.store.TakeAccountTransfer(r.Context(), crypto.HashToken(code))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "transfer not found or expired")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, claimTransferResponse{Vault: b64e(vault)})
}
