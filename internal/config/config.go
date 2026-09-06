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
	// BlobDir — директория с зашифрованными файлами.
	BlobDir string
	// MaxMediaBytes — лимит байтов ciphertext, без multipart-обвязки.
	MaxMediaBytes int
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
	}
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
