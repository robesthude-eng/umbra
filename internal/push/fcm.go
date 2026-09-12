// Package push отправляет уведомления на устройства через Firebase Cloud
// Messaging (HTTP v1). Legacy-API с одним «серверным ключом» Google отключила,
// поэтому запросы подписываются сервисным аккаунтом: JWT RS256 → access token
// → messages:send. Внешних зависимостей нет, только стандартная библиотека.
package push

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ErrTokenInvalid — токен устройства больше не действителен: его надо удалить
// из хранилища, иначе каждая отправка будет пропадать впустую.
var ErrTokenInvalid = errors.New("push: device token is not registered")

// Message — data-only уведомление для одного устройства. Блок notification
// сознательно не используется: входящий звонок должен показать не баннер,
// а полноэкранный экран, и решает это клиент.
type Message struct {
	Token        string
	Data         map[string]string
	CollapseKey  string
	HighPriority bool
	TTL          time.Duration
}

// serviceAccount — только те поля google-services аккаунта, что нужны для JWT.
type serviceAccount struct {
	Type        string `json:"type"`
	ProjectID   string `json:"project_id"`
	PrivateKey  string `json:"private_key"`
	ClientEmail string `json:"client_email"`
	TokenURI    string `json:"token_uri"`
}

const (
	scope           = "https://www.googleapis.com/auth/firebase.messaging"
	defaultTokenURI = "https://oauth2.googleapis.com/token"
	requestTimeout  = 10 * time.Second
	maxErrorBody    = 8 << 10
)

// FCM — отправитель через Firebase Cloud Messaging HTTP v1.
// Access token кэшируется: Google выдаёт его на час.
type FCM struct {
	projectID string
	email     string
	key       *rsa.PrivateKey
	tokenURI  string
	endpoint  string
	client    *http.Client

	mu      sync.Mutex
	token   string
	expires time.Time
}

// NewFCM разбирает JSON сервисного аккаунта Firebase. Пустой projectID
// берётся из самого файла.
func NewFCM(credentials []byte, projectID string) (*FCM, error) {
	var sa serviceAccount
	if err := json.Unmarshal(credentials, &sa); err != nil {
		return nil, fmt.Errorf("push: invalid credentials json: %w", err)
	}
	if strings.TrimSpace(sa.ClientEmail) == "" || strings.TrimSpace(sa.PrivateKey) == "" {
		return nil, errors.New("push: credentials must contain client_email and private_key")
	}
	if projectID == "" {
		projectID = sa.ProjectID
	}
	if projectID == "" {
		return nil, errors.New("push: empty project_id; set FCM_PROJECT_ID")
	}
	key, err := parsePrivateKey(sa.PrivateKey)
	if err != nil {
		return nil, err
	}
	tokenURI := strings.TrimSpace(sa.TokenURI)
	if tokenURI == "" {
		tokenURI = defaultTokenURI
	}
	return &FCM{
		projectID: projectID,
		email:     strings.TrimSpace(sa.ClientEmail),
		key:       key,
		tokenURI:  tokenURI,
		endpoint:  "https://fcm.googleapis.com/v1/projects/" + projectID + "/messages:send",
		client:    &http.Client{Timeout: requestTimeout},
	}, nil
}

// parsePrivateKey принимает и PKCS#8 (так выдаёт Google), и старый PKCS#1.
func parsePrivateKey(pemKey string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(pemKey))
	if block == nil {
		return nil, errors.New("push: private_key is not PEM")
	}
	if parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		key, ok := parsed.(*rsa.PrivateKey)
		if !ok {
			return nil, errors.New("push: private_key is not an RSA key")
		}
		return key, nil
	}
	key, err := x509.ParsePKCS1PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("push: cannot parse private_key: %w", err)
	}
	return key, nil
}

// ProjectID — проект Firebase, в который уходят уведомления (для логов).
func (f *FCM) ProjectID() string { return f.projectID }

// Send отправляет одно уведомление. ErrTokenInvalid означает, что токен
// пора удалить: приложение снесли или Firebase выдала новый.
func (f *FCM) Send(ctx context.Context, m Message) error {
	if strings.TrimSpace(m.Token) == "" {
		return errors.New("push: empty device token")
	}
	access, err := f.accessToken(ctx)
	if err != nil {
		return err
	}

	android := map[string]any{"priority": "NORMAL"}
	if m.HighPriority {
		android["priority"] = "HIGH"
	}
	if m.TTL > 0 {
		android["ttl"] = strconv.Itoa(int(m.TTL.Seconds())) + "s"
	}
	if m.CollapseKey != "" {
		android["collapse_key"] = m.CollapseKey
	}
	body, err := json.Marshal(map[string]any{
		"message": map[string]any{
			"token":   m.Token,
			"data":    m.Data,
			"android": android,
		},
	})
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+access)
	req.Header.Set("Content-Type", "application/json")

	resp, err := f.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(io.LimitReader(resp.Body, maxErrorBody))

	if resp.StatusCode == http.StatusOK {
		return nil
	}
	text := string(payload)
	if resp.StatusCode == http.StatusNotFound || resp.StatusCode == http.StatusGone ||
		strings.Contains(text, "UNREGISTERED") || strings.Contains(text, "INVALID_ARGUMENT") {
		return ErrTokenInvalid
	}
	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		// Скорее всего истёк или отозван access token — выбросим кэш.
		f.dropAccessToken()
	}
	return fmt.Errorf("push: fcm status %d: %s", resp.StatusCode, strings.TrimSpace(text))
}

// accessToken выдаёт кэшированный OAuth-токен или запрашивает новый.
func (f *FCM) accessToken(ctx context.Context) (string, error) {
	f.mu.Lock()
	if f.token != "" && time.Now().Before(f.expires) {
		cached := f.token
		f.mu.Unlock()
		return cached, nil
	}
	f.mu.Unlock()

	assertion, err := f.assertion(time.Now())
	if err != nil {
		return "", err
	}
	form := url.Values{}
	form.Set("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
	form.Set("assertion", assertion)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.tokenURI, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := f.client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	payload, _ := io.ReadAll(io.LimitReader(resp.Body, maxErrorBody))
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("push: oauth status %d: %s", resp.StatusCode, strings.TrimSpace(string(payload)))
	}
	var parsed struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(payload, &parsed); err != nil {
		return "", fmt.Errorf("push: oauth response: %w", err)
	}
	if parsed.AccessToken == "" {
		return "", errors.New("push: oauth response without access_token")
	}
	ttl := time.Duration(parsed.ExpiresIn) * time.Second
	if ttl <= time.Minute {
		ttl = time.Hour
	}

	f.mu.Lock()
	f.token = parsed.AccessToken
	f.expires = time.Now().Add(ttl - time.Minute)
	f.mu.Unlock()
	return parsed.AccessToken, nil
}

func (f *FCM) dropAccessToken() {
	f.mu.Lock()
	f.token = ""
	f.expires = time.Time{}
	f.mu.Unlock()
}

// assertion собирает JWT RS256 для обмена на access token.
func (f *FCM) assertion(now time.Time) (string, error) {
	claims, err := json.Marshal(map[string]any{
		"iss":   f.email,
		"scope": scope,
		"aud":   f.tokenURI,
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	if err != nil {
		return "", err
	}
	signing := base64url([]byte(`{"alg":"RS256","typ":"JWT"}`)) + "." + base64url(claims)
	digest := sha256.Sum256([]byte(signing))
	signature, err := rsa.SignPKCS1v15(rand.Reader, f.key, crypto.SHA256, digest[:])
	if err != nil {
		return "", err
	}
	return signing + "." + base64url(signature), nil
}

func base64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }
