package httpapi

import (
	"context"
	"html"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Предпросмотр ссылок делает СЕРВЕР, а не телефон: иначе любая
// присланная ссылка светила бы IP и User-Agent получателя ещё до клика.
// Защиты: только http/https, запрет внутренних адресов (SSRF), лимиты
// на размер, время и число редиректов, кэш с TTL.

const (
	linkPreviewTimeout   = 6 * time.Second
	linkPreviewMaxBody   = 512 << 10
	linkPreviewTTL       = 6 * time.Hour
	linkPreviewCacheMax  = 512
	linkPreviewMaxHops   = 3
	linkPreviewTextLimit = 300
)

type linkPreview struct {
	URL         string `json:"url"`
	Title       string `json:"title,omitempty"`
	Description string `json:"description,omitempty"`
	SiteName    string `json:"site_name,omitempty"`
	ImageURL    string `json:"image_url,omitempty"`
}

type linkPreviewEntry struct {
	preview linkPreview
	stored  time.Time
}

type linkPreviewCache struct {
	mu      sync.Mutex
	entries map[string]linkPreviewEntry
}

func newLinkPreviewCache() *linkPreviewCache {
	return &linkPreviewCache{entries: make(map[string]linkPreviewEntry)}
}

func (c *linkPreviewCache) get(key string) (linkPreview, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	entry, ok := c.entries[key]
	if !ok || time.Since(entry.stored) > linkPreviewTTL {
		if ok {
			delete(c.entries, key)
		}
		return linkPreview{}, false
	}
	return entry.preview, true
}

func (c *linkPreviewCache) put(key string, p linkPreview) {
	c.mu.Lock()
	defer c.mu.Unlock()
	// Простая защита от роста: переполненный кэш сбрасывается целиком.
	if len(c.entries) >= linkPreviewCacheMax {
		c.entries = make(map[string]linkPreviewEntry)
	}
	c.entries[key] = linkPreviewEntry{preview: p, stored: time.Now()}
}

var (
	metaRe  = regexp.MustCompile(`(?is)<meta\s+[^>]*>`)
	attrRe  = regexp.MustCompile(`(?is)(property|name|content)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))`)
	titleRe = regexp.MustCompile(`(?is)<title[^>]*>(.*?)</title>`)
)

// isBlockedIP отсекает адреса, куда сервер не должен ходить по просьбе клиента.
func isBlockedIP(ip net.IP) bool {
	if ip == nil {
		return true
	}
	if ip.IsLoopback() || ip.IsPrivate() || ip.IsUnspecified() ||
		ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsMulticast() {
		return true
	}
	// 100.64.0.0/10 (CGNAT) и 169.254.169.254 (метаданные облака) — тоже нельзя.
	if v4 := ip.To4(); v4 != nil {
		if v4[0] == 100 && v4[1] >= 64 && v4[1] <= 127 {
			return true
		}
	}
	return false
}

// safeDialContext проверяет адрес именно перед соединением, а не по имени хоста:
// так не проходит подмена DNS после проверки (DNS rebinding).
func safeDialContext(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	dialer := &net.Dialer{Timeout: linkPreviewTimeout}
	for _, ip := range ips {
		if isBlockedIP(ip.IP) {
			continue
		}
		conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip.IP.String(), port))
		if err == nil {
			return conn, nil
		}
	}
	return nil, &net.AddrError{Err: "address is not allowed", Addr: host}
}

func newLinkPreviewClient() *http.Client {
	return &http.Client{
		Timeout: linkPreviewTimeout,
		Transport: &http.Transport{
			DialContext:         safeDialContext,
			DisableKeepAlives:   true,
			TLSHandshakeTimeout: linkPreviewTimeout,
		},
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= linkPreviewMaxHops {
				return http.ErrUseLastResponse
			}
			if req.URL.Scheme != "http" && req.URL.Scheme != "https" {
				return http.ErrUseLastResponse
			}
			return nil
		},
	}
}

func clampText(s string) string {
	s = strings.TrimSpace(html.UnescapeString(s))
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > linkPreviewTextLimit {
		s = strings.TrimSpace(s[:linkPreviewTextLimit]) + "…"
	}
	return s
}

// parseLinkPreview вытаскивает og:*-теги и <title>. Полный парсер HTML здесь не нужен:
// нам достаточно четырёх полей из головы документа.
func parseLinkPreview(pageURL string, body []byte) linkPreview {
	out := linkPreview{URL: pageURL}
	for _, tag := range metaRe.FindAllString(string(body), 200) {
		key, content := "", ""
		for _, m := range attrRe.FindAllStringSubmatch(tag, -1) {
			value := m[3] + m[4] + m[5]
			switch strings.ToLower(m[1]) {
			case "property", "name":
				key = strings.ToLower(value)
			case "content":
				content = value
			}
		}
		if content == "" {
			continue
		}
		switch key {
		case "og:title", "twitter:title":
			if out.Title == "" {
				out.Title = clampText(content)
			}
		case "og:description", "twitter:description", "description":
			if out.Description == "" {
				out.Description = clampText(content)
			}
		case "og:site_name":
			if out.SiteName == "" {
				out.SiteName = clampText(content)
			}
		case "og:image", "og:image:secure_url", "twitter:image":
			if out.ImageURL == "" {
				if abs, err := absoluteURL(pageURL, content); err == nil {
					out.ImageURL = abs
				}
			}
		}
	}
	if out.Title == "" {
		if m := titleRe.FindStringSubmatch(string(body)); len(m) == 2 {
			out.Title = clampText(m[1])
		}
	}
	if out.SiteName == "" {
		if u, err := url.Parse(pageURL); err == nil {
			out.SiteName = strings.TrimPrefix(u.Host, "www.")
		}
	}
	return out
}

func absoluteURL(base, ref string) (string, error) {
	b, err := url.Parse(base)
	if err != nil {
		return "", err
	}
	r, err := url.Parse(strings.TrimSpace(ref))
	if err != nil {
		return "", err
	}
	out := b.ResolveReference(r)
	if out.Scheme != "http" && out.Scheme != "https" {
		return "", &url.Error{Op: "parse", URL: ref, Err: errUnsupportedScheme}
	}
	return out.String(), nil
}

var errUnsupportedScheme = &schemeError{}

type schemeError struct{}

func (e *schemeError) Error() string { return "unsupported scheme" }

// handleLinkPreview — GET /v1/link-preview?url=...
// Отвечает 204, если карточку показать нечего: клиенту проще отличить это от ошибки.
func (s *Server) handleLinkPreview(w http.ResponseWriter, r *http.Request) {
	raw := strings.TrimSpace(r.URL.Query().Get("url"))
	if raw == "" {
		writeError(w, http.StatusBadRequest, "url required")
		return
	}
	if len(raw) > 2048 {
		writeError(w, http.StatusBadRequest, "url too long")
		return
	}
	target, err := url.Parse(raw)
	if err != nil || (target.Scheme != "http" && target.Scheme != "https") || target.Host == "" {
		writeError(w, http.StatusBadRequest, "unsupported url")
		return
	}
	// Фрагмент на сервер не едет и лишь плодит копии в кэше.
	target.Fragment = ""
	key := target.String()

	if cached, ok := s.linkPreviews.get(key); ok {
		if cached.Title == "" && cached.Description == "" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		writeJSON(w, http.StatusOK, cached)
		return
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodGet, key, nil)
	if err != nil {
		writeError(w, http.StatusBadRequest, "unsupported url")
		return
	}
	req.Header.Set("User-Agent", "UmbraLinkPreview/1.0 (+https://umbra.invalid)")
	req.Header.Set("Accept", "text/html,application/xhtml+xml")
	req.Header.Set("Accept-Language", "ru,en;q=0.8")

	resp, err := s.linkClient.Do(req)
	if err != nil {
		writeError(w, http.StatusBadGateway, "preview unavailable")
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		// Отрицательный результат тоже кэшируем: иначе каждый показ чата бил бы в чужой сайт.
		s.linkPreviews.put(key, linkPreview{URL: key})
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if ct := resp.Header.Get("Content-Type"); ct != "" && !strings.Contains(strings.ToLower(ct), "html") {
		s.linkPreviews.put(key, linkPreview{URL: key})
		w.WriteHeader(http.StatusNoContent)
		return
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, linkPreviewMaxBody))
	if err != nil {
		writeError(w, http.StatusBadGateway, "preview unavailable")
		return
	}
	preview := parseLinkPreview(key, body)
	s.linkPreviews.put(key, preview)
	if preview.Title == "" && preview.Description == "" {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	writeJSON(w, http.StatusOK, preview)
}
