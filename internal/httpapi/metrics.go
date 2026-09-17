package httpapi

import (
	"bufio"
	"crypto/subtle"
	"errors"
	"log"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"umbra/server/internal/metrics"
)

// Метрики HTTP-слоя и выдача /metrics.
//
// Доступ закрыт отдельным токеном METRICS_TOKEN, а не сессией пользователя:
// снимать метрики должен мониторинг, а не клиент. Если токен не задан, эндпоинт
// отвечает 404: не подтверждаем даже факт его существования.

const (
	metricHTTPRequests = "umbra_http_requests_total"
	metricHTTPDuration = "umbra_http_request_duration_seconds"
	metricOTPSends     = "umbra_otp_sends_total"
	metricOTPVerify    = "umbra_otp_verify_total"
	metricPush         = "umbra_push_total"
)

// routeSegments — статические части путей. Всё остальное считается идентификатором
// и схлопывается в {id}: иначе кардинальность метрики растёт по числу пользователей.
var routeSegments = map[string]bool{
	"v1": true, "healthz": true, "metrics": true,
	"auth": true, "challenge": true, "verify": true, "request_code": true, "verify_code": true,
	"logout": true, "refresh": true, "register": true, "prekeys": true, "keys": true,
	"account": true, "sessions": true, "security": true, "code": true, "profile": true,
	"avatar": true, "privacy": true, "burn": true, "transfer": true, "claim": true,
	"revoke_others": true, "invites": true, "uses": true,
	"messages": true, "media": true, "chats": true, "groups": true, "channels": true,
	"members": true, "contacts": true, "discover": true, "typing": true, "read": true,
	"link-preview": true, "calls": true, "signal": true, "status": true, "turn": true,
	"push": true, "devices": true, "users": true, "by-username": true, "presence": true,
	"ws": true,
}

// routeLabel строит шаблон маршрута из пути запроса. Шаблон самого мукса здесь
// недоступен: middleware стоит до маршрутизации.
func routeLabel(path string) string {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	if len(parts) == 1 && parts[0] == "" {
		return "/"
	}
	if len(parts) > 8 {
		return "other"
	}
	for i, p := range parts {
		if !routeSegments[p] {
			parts[i] = "{id}"
		}
	}
	return "/" + strings.Join(parts, "/")
}

// statusWriter запоминает код ответа и пропускает Hijack/Flush: без этого упадёт
// апгрейд WebSocket на /v1/ws.
type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	if w.status == 0 {
		w.status = code
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if w.status == 0 {
		w.status = http.StatusOK
	}
	return w.ResponseWriter.Write(b)
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (w *statusWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := w.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, errors.New("response writer does not support hijacking")
	}
	return h.Hijack()
}

// metricsMiddleware считает запросы и их длительность.
func metricsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w}
		next.ServeHTTP(sw, r)
		if sw.status == 0 {
			sw.status = http.StatusOK
		}
		route := routeLabel(r.URL.Path)
		metrics.CounterInc(metricHTTPRequests, "HTTP requests by route and status code",
			"route", route, "method", r.Method, "code", strconv.Itoa(sw.status))
		metrics.Observe(metricHTTPDuration, "HTTP request duration in seconds",
			time.Since(start).Seconds(), "route", route)
	})
}

// observeOTPSend и observeOTPVerify вызываются из OTP-путей (otp.go, otp_security.go).
// result: sent | throttled | capacity | delivery_failed | ok | invalid | expired.
func observeOTPSend(result string) {
	metrics.CounterInc(metricOTPSends, "OTP send attempts by result", "result", result)
}

func observeOTPVerify(result string) {
	metrics.CounterInc(metricOTPVerify, "OTP verification attempts by result", "result", result)
}

// observePush вызывается после отправки push: result = ok | error | skipped.
func observePush(result string) {
	metrics.CounterInc(metricPush, "Push notifications by result", "result", result)
}

// handleMetrics отдаёт метрики в Prometheus text format.
func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	token := s.cfg.MetricsToken
	if token == "" {
		http.NotFound(w, r)
		return
	}
	provided, ok := bearerToken(r)
	if !ok {
		provided = r.URL.Query().Get("token")
	}
	if subtle.ConstantTimeCompare([]byte(provided), []byte(token)) != 1 {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if err := metrics.WriteText(w); err != nil {
		log.Printf("metrics: %v", err)
	}
}
