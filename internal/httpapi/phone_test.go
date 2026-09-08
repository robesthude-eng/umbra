package httpapi

import (
	"strings"
	"testing"
)

func TestNormalizePhone(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"+7 999 123-45-67", "+79991234567"},
		{"8 (999) 123-45-67", "+79991234567"},   // РФ: 8 → +7
		{"89991234567", "+79991234567"},
		{"9991234567", "+79991234567"},          // 10 цифр без кода → РФ
		{"0079991234567", "+79991234567"},       // международный 00
		{"+1 (202) 555-0147", "+12025550147"},
		{"+49 30 901820", "+4930901820"},
		{"+79991234567", "+79991234567"},
		{"\u00a0+7 999 123 45 67\u00a0", "+79991234567"}, // неразрывные пробелы
	}
	for _, c := range cases {
		got, err := NormalizePhone(c.in)
		if err != nil {
			t.Errorf("NormalizePhone(%q): неожиданная ошибка %v", c.in, err)
			continue
		}
		if got != c.want {
			t.Errorf("NormalizePhone(%q) = %q, ожидалось %q", c.in, got, c.want)
		}
	}
}

func TestNormalizePhoneInvalid(t *testing.T) {
	for _, in := range []string{
		"", "   ", "+", "abc", "+0 123456", // первая цифра 0
		"123",              // слишком короткий (<7)
		"+1234567890123456", // 16 цифр — больше E.164
		"+7999abc", "+7 999#123",
	} {
		if got, err := NormalizePhone(in); err == nil {
			t.Errorf("NormalizePhone(%q) = %q, ожидалась ошибка", in, got)
		}
	}
}

func TestPhoneHash(t *testing.T) {
	h := PhoneHash("+79991234567")
	if len(h) != 64 {
		t.Fatalf("ожидался hex sha256 (64 символа), получено %d", len(h))
	}
	if !validPhoneHash(h) {
		t.Fatalf("хэш не проходит собственную валидацию: %q", h)
	}
	if h != PhoneHash("+79991234567") {
		t.Fatal("хэш не детерминирован")
	}
	if h == PhoneHash("+79991234568") {
		t.Fatal("разные номера дали одинаковый хэш")
	}
	if !strings.EqualFold(h, strings.ToLower(h)) {
		t.Fatal("хэш должен быть в нижнем регистре")
	}
}
