package crypto

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"testing"
)

func TestRandomBytes(t *testing.T) {
	b, err := RandomBytes(32)
	if err != nil {
		t.Fatalf("RandomBytes: %v", err)
	}
	if len(b) != 32 {
		t.Fatalf("ожидалось 32 байта, получено %d", len(b))
	}
	// не должен возвращать все нули
	if bytes.Equal(b, make([]byte, 32)) {
		t.Fatal("RandomBytes вернул все нули")
	}
	// два вызова должны давать разные значения
	b2, _ := RandomBytes(32)
	if bytes.Equal(b, b2) {
		t.Fatal("RandomBytes вернул одинаковые значения")
	}
}

func TestNewChallenge(t *testing.T) {
	c, err := NewChallenge()
	if err != nil {
		t.Fatalf("NewChallenge: %v", err)
	}
	raw, err := base64.RawStdEncoding.DecodeString(c)
	if err != nil {
		t.Fatalf("challenge не является base64: %v", err)
	}
	if len(raw) != 32 {
		t.Fatalf("ожидалось 32 байта, получено %d", len(raw))
	}
	// уникальность
	c2, _ := NewChallenge()
	if c == c2 {
		t.Fatal("challenge не уникален")
	}
}

func TestNewToken(t *testing.T) {
	tok, err := NewToken()
	if err != nil {
		t.Fatalf("NewToken: %v", err)
	}
	if tok == "" {
		t.Fatal("пустой токен")
	}
	// base64url
	if _, err := base64.RawURLEncoding.DecodeString(tok); err != nil {
		t.Fatalf("токен не base64url: %v", err)
	}
	tok2, _ := NewToken()
	if tok == tok2 {
		t.Fatal("токены не уникальны")
	}
}

func TestHashToken(t *testing.T) {
	h1 := HashToken("abc")
	h2 := HashToken("abc")
	if h1 != h2 {
		t.Fatal("HashToken недетерминирован")
	}
	if h1 == HashToken("abd") {
		t.Fatal("разные токены дали одинаковый хэш")
	}
	// хэш не должен содержать сам токен
	if bytes.Contains([]byte(h1), []byte("abc")) {
		t.Fatal("хэш содержит исходный токен")
	}
}

func TestVerifyEd25519(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("генерация ключа: %v", err)
	}
	msg := []byte("сообщение для подписи")
	sig := ed25519.Sign(priv, msg)

	if !VerifyEd25519(pub, msg, sig) {
		t.Fatal("валидная подпись не прошла проверку")
	}
	if VerifyEd25519(pub, []byte("другое сообщение"), sig) {
		t.Fatal("подпись прошла проверку для другого сообщения")
	}

	// другой открытый ключ
	pub2, priv2, _ := ed25519.GenerateKey(rand.Reader)
	_ = priv2
	if VerifyEd25519(pub2, msg, sig) {
		t.Fatal("подпись прошла проверку чужим ключом")
	}

	// неверная длина ключа
	if VerifyEd25519([]byte("короткий"), msg, sig) {
		t.Fatal("неверная длина ключа не отклонена")
	}
}
