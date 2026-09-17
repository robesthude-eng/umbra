package model

import "time"

// OTPCode — выданный код подтверждения (v0.19: состояние хранится в Store,
// а не в памяти процесса, поэтому код переживает рестарт сервера).
type OTPCode struct {
	Phone     string
	Purpose   string
	RequestID string
	CodeHash  string
	Binding   string
	Device    string
	Legacy    bool
	Attempts  int
	CreatedAt time.Time
	ExpiresAt time.Time
}

// OTPBudget — бюджет отправок и неудачных попыток на номер.
type OTPBudget struct {
	Phone       string
	WindowStart time.Time
	LastSentAt  time.Time
	Sends       int
	Failures    int
}

// Invite — инвайт-код, выданный владельцем сервера. Сам код не хранится,
// только его хеш (как и токены сессий).
type Invite struct {
	ID        string
	OwnerID   string
	CodeHash  string
	Label     string
	MaxUses   int
	Uses      int
	CreatedAt time.Time
	ExpiresAt time.Time
	RevokedAt *time.Time
}

// Active сообщает, можно ли ещё воспользоваться кодом.
func (i *Invite) Active(now time.Time) bool {
	if i == nil || i.RevokedAt != nil {
		return false
	}
	if !i.ExpiresAt.IsZero() && !i.ExpiresAt.After(now) {
		return false
	}
	if i.MaxUses > 0 && i.Uses >= i.MaxUses {
		return false
	}
	return true
}
