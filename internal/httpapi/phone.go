package httpapi

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
)

// Нормализация и хэширование номеров телефонов.
//
// Правила NormalizePhone ДОЛЖНЫ совпадать с клиентской реализацией
// (android/.../data/contacts/PhoneNumbers.kt): от расхождения контакты
// не найдут друг друга. Менять только синхронно на обеих сторонах.
//
// Правила:
//  1. Убираем все символы, кроме цифр и ведущего '+'.
//  2. "00" в начале заменяется на "+" (международный формат).
//  3. Без "+": 11 цифр, начинающихся с '8', трактуются как РФ → "+7...";
//     10 цифр — как РФ без кода страны → "+7..."; иначе считаем, что код
//     страны уже есть, и просто добавляем "+".
//  4. Результат обязан соответствовать E.164: '+' + 7..15 цифр, первая не '0'.

var errInvalidPhone = errors.New("invalid phone number")

// NormalizePhone приводит номер к E.164 или возвращает errInvalidPhone.
func NormalizePhone(raw string) (string, error) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", errInvalidPhone
	}
	hasPlus := s[0] == '+'
	var b strings.Builder
	b.Grow(len(s))
	for i, r := range s {
		switch {
		case r >= '0' && r <= '9':
			b.WriteRune(r)
		case i == 0 && r == '+':
			// уже учли hasPlus
		default:
			// пробелы, скобки, дефисы, точки игнорируем
			if r != ' ' && r != '-' && r != '(' && r != ')' && r != '.' && r != '\u00a0' {
				return "", errInvalidPhone
			}
		}
	}
	digits := b.String()
	if strings.HasPrefix(digits, "00") {
		digits = digits[2:]
		hasPlus = true
	}
	if !hasPlus {
		switch {
		case len(digits) == 11 && digits[0] == '8':
			digits = "7" + digits[1:]
		case len(digits) == 10:
			digits = "7" + digits
		}
	}
	if len(digits) < 7 || len(digits) > 15 || digits[0] == '0' {
		return "", errInvalidPhone
	}
	return "+" + digits, nil
}

// PhoneHash — SHA-256(hex) от нормализованного номера с доменным префиксом.
// Именно эти хэши клиент присылает в /v1/contacts/discover: сервер не видит
// телефонную книгу в открытом виде, только хэши.
func PhoneHash(normalized string) string {
	sum := sha256.Sum256([]byte("umbra-phone:" + normalized))
	return hex.EncodeToString(sum[:])
}

// validPhoneHash проверяет формат хэша из запроса discover (64 hex-символа).
func validPhoneHash(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, r := range s {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return false
		}
	}
	return true
}

// validDisplayName ограничивает длину отображаемого имени (в рунах).
func validDisplayName(s string) bool {
	n := len([]rune(s))
	return n >= 1 && n <= 64
}
