package httpapi

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
	"sync"
	"time"
)

type rateWindow struct {
	count int
	end   time.Time
}
type requestLimiter struct {
	mu             sync.Mutex
	entries        map[string]rateWindow
	trustedProxies []netip.Prefix
}

func newRequestLimiter(trustedProxies ...netip.Prefix) *requestLimiter {
	return &requestLimiter{entries: make(map[string]rateWindow), trustedProxies: trustedProxies}
}

func (l *requestLimiter) trusted(addr netip.Addr) bool {
	for _, prefix := range l.trustedProxies {
		if prefix.Contains(addr) {
			return true
		}
	}
	return false
}

// Only an explicitly trusted direct peer may supply X-Forwarded-For.
// Walk from the proxy towards the client and stop at the first untrusted hop;
// anything to its left may have been supplied by that client.
func (l *requestLimiter) clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	peer, err := netip.ParseAddr(host)
	if err != nil {
		return host
	}
	peer = peer.Unmap()
	if !l.trusted(peer) {
		return peer.String()
	}
	forwarded := strings.Join(r.Header.Values("X-Forwarded-For"), ",")
	if forwarded == "" || len(forwarded) > 2048 {
		return peer.String()
	}
	hops := strings.Split(forwarded, ",")
	if len(hops) > 32 {
		return peer.String()
	}
	current := peer
	for i := len(hops) - 1; i >= 0; i-- {
		if !l.trusted(current) {
			break
		}
		addr, err := netip.ParseAddr(strings.TrimSpace(hops[i]))
		if err != nil || addr.Zone() != "" {
			return peer.String()
		}
		current = addr.Unmap()
	}
	return current.String()
}

func (l *requestLimiter) wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" {
			next.ServeHTTP(w, r)
			return
		}
		ip := l.clientIP(r)
		category, limit := "api", 600
		if strings.HasPrefix(r.URL.Path, "/v1/auth/") || strings.HasSuffix(r.URL.Path, "/prekeys") {
			category, limit = "auth", 120
		}
		if r.URL.Path == "/v1/register" {
			category, limit = "register", 30
		}
		// Поиск контактов по хэшам номеров: защита от перебора телефонной базы.
		if r.URL.Path == "/v1/contacts/discover" {
			category, limit = "discover", 30
		}
		// Приём кода переноса — перебор кода ограничиваем жёстко.
		if r.URL.Path == "/v1/account/transfer/claim" {
			category, limit = "transfer", 20
		}
		// OTP (код в Telegram): запрос кода и проверка — тоже жёсткие лимиты.
		if r.URL.Path == "/v1/auth/request_code" {
			category, limit = "otp_request", 6
		}
		if r.URL.Path == "/v1/auth/verify_code" {
			category, limit = "otp_verify", 30
		}
		if !l.allow(category+":"+ip, limit, time.Now()) {
			w.Header().Set("Retry-After", "60")
			writeError(w, http.StatusTooManyRequests, "too many requests")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func (l *requestLimiter) allow(key string, limit int, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	value, ok := l.entries[key]
	if !ok || !value.end.After(now) {
		for k, v := range l.entries {
			if !v.end.After(now) {
				delete(l.entries, k)
			}
		}
		if len(l.entries) >= 16384 {
			return false
		}
		value = rateWindow{end: now.Add(time.Minute)}
	}
	if value.count >= limit {
		return false
	}
	value.count++
	l.entries[key] = value
	return true
}
