package httpapi

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// iceServerResponse — один ICE-сервер в формате, который понимает WebRTC.
type iceServerResponse struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

type iceConfigResponse struct {
	IceServers []iceServerResponse `json:"ice_servers"`
	// TTL — сколько секунд учётка действительна; клиент запрашивает новую перед звонком.
	TTL int `json:"ttl"`
}

// turnCredentials выдаёт временную пару логин/пароль для coturn в режиме
// use-auth-secret (TURN REST API): логин — "<истекает>:<user_id>", пароль —
// base64 от HMAC-SHA1 по логину и общему секрету. Секрет остаётся на сервере,
// поэтому из APK нельзя вытащить постоянный доступ к ретрансляции.
func turnCredentials(secret, userID string, ttl time.Duration, now time.Time) (string, string) {
	username := strconv.FormatInt(now.Add(ttl).Unix(), 10) + ":" + userID
	mac := hmac.New(sha1.New, []byte(secret))
	mac.Write([]byte(username))
	return username, base64.StdEncoding.EncodeToString(mac.Sum(nil))
}

// handleIceConfig — GET /v1/turn. Список ICE-серверов для звонящего.
// Медиа через сервер Umbra не идёт: TURN — отдельная служба, здесь только доступ к ней.
func (s *Server) handleIceConfig(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)

	ttl := s.cfg.TurnTTL
	if ttl <= 0 {
		ttl = time.Hour
	}

	servers := make([]iceServerResponse, 0, 2)
	if urls := splitIceURLs(s.cfg.StunURL); len(urls) > 0 {
		servers = append(servers, iceServerResponse{URLs: urls})
	}
	if urls := splitIceURLs(s.cfg.TurnURL); len(urls) > 0 {
		entry := iceServerResponse{URLs: urls}
		switch {
		case s.cfg.TurnSecret != "":
			entry.Username, entry.Credential = turnCredentials(s.cfg.TurnSecret, userID, ttl, time.Now())
		case s.cfg.TurnUsername != "":
			entry.Username, entry.Credential = s.cfg.TurnUsername, s.cfg.TurnPassword
		}
		servers = append(servers, entry)
	}

	writeJSON(w, http.StatusOK, iceConfigResponse{IceServers: servers, TTL: int(ttl.Seconds())})
}

// splitIceURLs разбирает список адресов через запятую и убирает пробелы.
func splitIceURLs(value string) []string {
	out := make([]string, 0, 2)
	for _, part := range strings.Split(value, ",") {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}
