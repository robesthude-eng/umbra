// Package crypto — минимальный набор крипто-примитивов для сервера.
// Используется только стандартная библиотека Go (проверенные реализации).
// E2E-шифрование сообщений выполняется НА КЛИЕНТЕ (Signal Protocol / X3DH),
// сервер лишь хранит открытые ключи и шифротекст.
package crypto

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
)

// RandomBytes возвращает n криптостойких случайных байт.
func RandomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	return b, nil
}

// NewChallenge генерирует одноразовый nonce для challenge-response аутентификации.
func NewChallenge() (string, error) {
	b, err := RandomBytes(32)
	if err != nil {
		return "", err
	}
	return base64.RawStdEncoding.EncodeToString(b), nil
}

// NewToken генерирует сессионный токен (32 случайных байта, base64url).
func NewToken() (string, error) {
	b, err := RandomBytes(32)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// HashToken — хэш токена для хранения на сервере.
// Сервер не хранит токен в открытом виде (защита от утечки БД).
func HashToken(token string) string {
	h := sha256.Sum256([]byte(token))
	return base64.RawStdEncoding.EncodeToString(h[:])
}

// VerifyEd25519 проверяет подпись sig над сообщением msg открытым ключом pub.
func VerifyEd25519(pub, msg, sig []byte) bool {
	if len(pub) != ed25519.PublicKeySize {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(pub), msg, sig)
}
