// Package config читает настройки сервера из переменных окружения.
// Все секреты задаются через env, никогда не хардкодятся в коде.
package config

import (
	"os"
	"strconv"
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
	// TokenTTL — срок жизни сессионного токена.
	TokenTTL time.Duration
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
}

func Load() *Config {
	return &Config{
		ListenAddr:      getenv("LISTEN_ADDR", ":8080"),
		Store:           getenv("STORE", "memory"),
		DatabaseURL:     getenv("DATABASE_URL", ""),
		TokenTTL:        time.Duration(getenvInt("TOKEN_TTL_SECONDS", 86400)) * time.Second,
		MaxMessageBytes: int64(getenvInt("MAX_MESSAGE_BYTES", 2_097_152)), // 2 MiB по умолчанию
		BlobDir:         getenv("BLOB_DIR", "./data/blobs"),
		MaxMediaBytes:   getenvPositiveInt("MAX_MEDIA_BYTES", DefaultMaxMediaBytes),
		BlobStoreType:   getenv("BLOB_STORE_TYPE", "file"),
		S3Endpoint:      getenv("S3_ENDPOINT", ""),
		S3AccessKey:     getenv("S3_ACCESS_KEY", ""),
		S3SecretKey:     getenv("S3_SECRET_KEY", ""),
		S3Bucket:        getenv("S3_BUCKET", ""),
		S3Region:        getenv("S3_REGION", ""),
		S3UseSSL:        getenvBool("S3_USE_SSL", true),
		MaxUserMediaBytes: int64(getenvInt("MAX_USER_MEDIA_BYTES", 0)),
	}
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
