// Package config читает настройки сервера из переменных окружения.
// Все секреты задаются через env, никогда не хардкодятся в коде.
package config

import (
	"errors"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"time"
)

const DefaultMaxMediaBytes = 50 << 20

type Config struct {
	// ListenAddr — адрес, на котором слушает HTTP/WS сервер.
	ListenAddr string
	// Store — "memory" (для локального запуска/тестов) или "postgres" (продакшн).
	Store string
	// DatabaseURL — DSN PostgreSQL (используется при Store=postgres).
	DatabaseURL string
	// TokenTTL — срок сессии после входа или последнего продления.
	TokenTTL time.Duration
	// AllowLegacyAuth enables username-only key authentication for old clients.
	// Phone accounts always require OTP, including when this option is enabled.
	AllowLegacyAuth bool
	// TrustedProxies is a comma-separated list of proxy IP addresses/CIDRs.
	// Forwarded client addresses are ignored unless the direct peer is trusted.
	TrustedProxies string
	// MaxMessageBytes — лимит размера одного сообщения (защита от переполнения).
	MaxMessageBytes int64
	// BlobDir — директория с зашифрованными файлами (при BlobStoreType=file).
	BlobDir string
	// MaxMediaBytes — лимит байтов ciphertext, без multipart-обвязки.
	MaxMediaBytes int
	// BlobStoreType — "file" (по умолчанию) или "s3".
	BlobStoreType string
	// S3Endpoint, S3AccessKey, S3SecretKey, S3Bucket, S3Region, S3UseSSL —
	// настройки объектного хранилища (при BlobStoreType=s3).
	S3Endpoint  string
	S3AccessKey string
	S3SecretKey string
	S3Bucket    string
	S3Region    string
	S3UseSSL    bool
	// MaxUserMediaBytes — квота суммарного объёма медиа на пользователя (0 = без лимита).
	MaxUserMediaBytes int64
	// MediaOpenAccess возвращает прежнее поведение MVP: медиа без области
	// видимости (chat_id/recipient_id) может скачать любой авторизованный
	// пользователь. Нужно только для файлов, загруженных клиентами до 0.16.16.
	// По умолчанию выключено: иначе знание id = доступ к файлу.
	MediaOpenAccess bool
	// StunURL — публичный STUN для сбора ICE-кандидатов звонка.
	StunURL string
	// TurnURL — TURN-сервер для ретрансляции, когда прямое соединение не поднялось.
	// Можно перечислить несколько адресов через запятую.
	TurnURL string
	// TurnSecret — общий секрет coturn (--use-auth-secret). Если задан, сервер
	// выдаёт временные учётки, и постоянный пароль в приложение не попадает.
	TurnSecret string
	// TurnUsername, TurnPassword — постоянная учётка TURN (запасной вариант).
	TurnUsername string
	TurnPassword string
	// TurnTTL — срок жизни временной учётки TURN.
	TurnTTL time.Duration
	// FCMKeyFile — путь к JSON сервисного аккаунта Firebase.
	// Пусто и FCMKeyJSON пуст = push-уведомления выключены.
	FCMKeyFile string
	// FCMKeyJSON — тот же JSON строкой (удобно для секретов без файла).
	FCMKeyJSON string
	// FCMProjectID переопределяет project_id из файла (обычно не нужен).
	FCMProjectID string
	// TelegramBotToken — токен бота для доставки OTP-кодов (пусто = коды отключены).
	TelegramBotToken string
	// TelegramAPIBase — если задан, все вызовы Bot API идут через этот base URL
	// (например, Cloudflare Worker-релей), а не напрямую в api.telegram.org.
	TelegramAPIBase string
	// TelegramAPIKey — секрет для релея (заголовок x-umbra-key).
	TelegramAPIKey string
	// TelegramChatID — чат, в который приходят OTP-коды с любых номеров (владелец).
	TelegramChatID int64
}

func Load() *Config {
	return &Config{
		ListenAddr:        getenv("LISTEN_ADDR", ":8080"),
		Store:             getenv("STORE", "memory"),
		DatabaseURL:       getenv("DATABASE_URL", ""),
		TokenTTL:          time.Duration(getenvInt("TOKEN_TTL_SECONDS", 30*24*60*60)) * time.Second,
		AllowLegacyAuth:   getenvBool("ALLOW_LEGACY_AUTH", false),
		TrustedProxies:    getenv("TRUSTED_PROXIES", ""),
		MaxMessageBytes:   int64(getenvInt("MAX_MESSAGE_BYTES", 2_097_152)), // 2 MiB по умолчанию
		BlobDir:           getenv("BLOB_DIR", "./data/blobs"),
		MaxMediaBytes:     getenvPositiveInt("MAX_MEDIA_BYTES", DefaultMaxMediaBytes),
		BlobStoreType:     getenv("BLOB_STORE_TYPE", "file"),
		S3Endpoint:        getenv("S3_ENDPOINT", ""),
		S3AccessKey:       getenv("S3_ACCESS_KEY", ""),
		S3SecretKey:       getenv("S3_SECRET_KEY", ""),
		S3Bucket:          getenv("S3_BUCKET", ""),
		S3Region:          getenv("S3_REGION", ""),
		S3UseSSL:          getenvBool("S3_USE_SSL", true),
		MaxUserMediaBytes: int64(getenvInt("MAX_USER_MEDIA_BYTES", 0)),
		MediaOpenAccess:   getenvBool("MEDIA_LEGACY_OPEN_ACCESS", false),
		StunURL:           getenv("STUN_URL", "stun:stun.l.google.com:19302"),
		TurnURL:           getenv("TURN_URL", ""),
		TurnSecret:        getenv("TURN_SECRET", ""),
		TurnUsername:      getenv("TURN_USERNAME", ""),
		TurnPassword:      getenv("TURN_PASSWORD", ""),
		TurnTTL:           time.Duration(getenvPositiveInt("TURN_TTL_SECONDS", 3600)) * time.Second,
		FCMKeyFile:        getenv("FCM_CREDENTIALS_FILE", ""),
		FCMKeyJSON:        getenv("FCM_CREDENTIALS_JSON", ""),
		FCMProjectID:      getenv("FCM_PROJECT_ID", ""),
		TelegramBotToken:  getenv("TELEGRAM_BOT_TOKEN", ""),
		TelegramAPIBase:   getenv("TELEGRAM_API_BASE", ""),
		TelegramAPIKey:    getenv("TELEGRAM_API_KEY", ""),
		TelegramChatID:    int64(getenvInt("TELEGRAM_CHAT_ID", 0)),
	}
}

func (c *Config) Validate() error {
	if c.Store != "memory" && c.Store != "postgres" {
		return errors.New("STORE must be memory or postgres")
	}
	if c.Store == "postgres" && c.DatabaseURL == "" {
		return errors.New("DATABASE_URL is required for PostgreSQL")
	}
	if c.BlobStoreType != "file" && c.BlobStoreType != "s3" {
		return errors.New("BLOB_STORE_TYPE must be file or s3")
	}
	if c.BlobStoreType == "s3" && (c.S3Endpoint == "" || c.S3Bucket == "" || c.S3AccessKey == "" || c.S3SecretKey == "") {
		return errors.New("S3 endpoint, bucket and credentials are required")
	}
	if c.TokenTTL <= 0 || c.TokenTTL > 365*24*time.Hour {
		return errors.New("TOKEN_TTL_SECONDS must be positive and at most one year")
	}
	if c.MaxMessageBytes <= 0 {
		return errors.New("MAX_MESSAGE_BYTES must be positive")
	}
	if c.MaxUserMediaBytes < 0 {
		return errors.New("MAX_USER_MEDIA_BYTES must not be negative")
	}
	if c.TurnTTL <= 0 || c.TurnTTL > 24*time.Hour {
		return errors.New("TURN_TTL_SECONDS must be positive and at most one day")
	}
	if _, err := ParseTrustedProxies(c.TrustedProxies); err != nil {
		return err
	}
	return nil
}

func ParseTrustedProxies(value string) ([]netip.Prefix, error) {
	var prefixes []netip.Prefix
	if strings.TrimSpace(value) == "" {
		return prefixes, nil
	}
	for _, part := range strings.Split(value, ",") {
		part = strings.TrimSpace(part)
		if addr, err := netip.ParseAddr(part); err == nil && addr.Zone() == "" {
			addr = addr.Unmap()
			prefixes = append(prefixes, netip.PrefixFrom(addr, addr.BitLen()))
			continue
		}
		prefix, err := netip.ParsePrefix(part)
		if err != nil || prefix.Bits() == 0 || prefix.Addr().Is4In6() {
			return nil, errors.New("TRUSTED_PROXIES must contain explicit proxy IPs or CIDRs; wildcard networks are not allowed")
		}
		prefixes = append(prefixes, prefix.Masked())
	}
	return prefixes, nil
}

func getenvBool(key string, def bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	switch v {
	case "1", "true", "TRUE", "yes", "YES", "on", "ON":
		return true
	case "0", "false", "FALSE", "no", "NO", "off", "OFF":
		return false
	}
	return def
}

func getenvPositiveInt(key string, def int) int {
	n := getenvInt(key, def)
	if n <= 0 {
		return def
	}
	return n
}

func getenv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getenvInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}
