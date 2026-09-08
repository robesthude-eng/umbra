package httpapi

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

type rateWindow struct { count int; end time.Time }
type requestLimiter struct { mu sync.Mutex; entries map[string]rateWindow }
func newRequestLimiter() *requestLimiter { return &requestLimiter{entries:make(map[string]rateWindow)} }

func (l *requestLimiter) wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/healthz" { next.ServeHTTP(w,r); return }
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil { ip=r.RemoteAddr }
		// Не доверяем X-Forwarded-For от произвольного клиента.
		category, limit := "api", 600
		if strings.HasPrefix(r.URL.Path,"/v1/auth/") || strings.HasSuffix(r.URL.Path,"/prekeys") { category,limit="auth",120 }
		if r.URL.Path == "/v1/register" { category,limit="register",30 }
		// Поиск контактов по хэшам номеров: защита от перебора телефонной базы.
		if r.URL.Path == "/v1/contacts/discover" { category,limit="discover",30 }
		// Приём кода переноса — перебор кода ограничиваем жёстко.
		if r.URL.Path == "/v1/account/transfer/claim" { category,limit="transfer",20 }
		if !l.allow(category+":"+ip,limit,time.Now()) {
			w.Header().Set("Retry-After","60")
			writeError(w,http.StatusTooManyRequests,"too many requests"); return
		}
		next.ServeHTTP(w,r)
	})
}

func (l *requestLimiter) allow(key string, limit int, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	value, ok := l.entries[key]
	if !ok || !value.end.After(now) {
		for k,v := range l.entries { if !v.end.After(now) { delete(l.entries,k) } }
		if len(l.entries) >= 16384 { return false }
		value=rateWindow{end:now.Add(time.Minute)}
	}
	if value.count >= limit { return false }
	value.count++
	l.entries[key]=value
	return true
}
