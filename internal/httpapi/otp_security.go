package httpapi

import (
	"crypto/subtle"
	"errors"
	"sync"
	"time"
	"umbra/server/internal/crypto"
)

const (
	otpCooldown    = time.Minute
	otpWindow      = 30 * time.Minute
	otpMaxSends    = 5
	otpMaxFailures = 10
	otpMaxPhones   = 4096
)

var (
	otpErrExpired  = errors.New("код не найден или истёк")
	otpErrTooMany  = errors.New("слишком много попыток")
	otpErrInvalid  = errors.New("неверный код")
	otpErrCapacity = errors.New("слишком много запросов")
)

type otpEntry struct {
	codeHash, requestID, binding, device string
	expires                              time.Time
	attempts                             int
	legacy                               bool
}
type otpBudget struct {
	start, lastSent time.Time
	sends, failures int
}

// Single-process bounded state. Resending/consuming never resets phone budgets.
type otpStore struct {
	mu        sync.Mutex
	m         map[string]*otpEntry
	budgets   map[string]*otpBudget
	now       func() time.Time
	nextSweep time.Time
}

func newOTPStore() *otpStore {
	return &otpStore{m: make(map[string]*otpEntry), budgets: make(map[string]*otpBudget), now: time.Now}
}
func (o *otpStore) sweep(now time.Time) {
	if now.Before(o.nextSweep) {
		return
	}
	o.nextSweep = now.Add(time.Minute)
	for k, e := range o.m {
		if !e.expires.After(now) {
			delete(o.m, k)
		}
	}
	for k, b := range o.budgets {
		if !b.start.Add(otpWindow).After(now) && !b.lastSent.Add(otpCooldown).After(now) && o.m[k+":login"] == nil && o.m[k+":delete_account"] == nil {
			delete(o.budgets, k)
		}
	}
}
func (o *otpStore) issue(phone, purpose, binding, device, code string, legacy bool) (string, time.Duration, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	now := o.now()
	o.sweep(now)
	b := o.budgets[phone]
	if b == nil {
		if len(o.budgets) >= otpMaxPhones {
			return "", time.Minute, otpErrCapacity
		}
		b = &otpBudget{start: now}
		o.budgets[phone] = b
	}
	if !b.start.Add(otpWindow).After(now) {
		b.start = now
		b.sends = 0
		b.failures = 0
	}
	if b.failures >= otpMaxFailures || b.sends >= otpMaxSends {
		return "", b.start.Add(otpWindow).Sub(now), otpErrTooMany
	}
	if retry := b.lastSent.Add(otpCooldown).Sub(now); retry > 0 {
		return "", retry, otpErrTooMany
	}
	id, err := crypto.NewToken()
	if err != nil {
		return "", 0, err
	}
	b.sends++
	b.lastSent = now
	o.m[phone+":"+purpose] = &otpEntry{requestID: id, codeHash: crypto.HashToken(id + ":" + code), expires: now.Add(otpTTL), binding: binding, device: device, legacy: legacy}
	return id, 0, nil
}
func (o *otpStore) invalidate(phone, purpose, id string) {
	o.mu.Lock()
	defer o.mu.Unlock()
	key := phone + ":" + purpose
	if e := o.m[key]; e != nil && e.requestID == id {
		delete(o.m, key)
	}
}
func (o *otpStore) verify(phone, purpose, binding, id, code string) (string, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	now := o.now()
	o.sweep(now)
	key := phone + ":" + purpose
	e := o.m[key]
	b := o.budgets[phone]
	if e == nil || b == nil || !e.expires.After(now) {
		delete(o.m, key)
		return "", otpErrExpired
	}
	if e.binding != binding || (id != e.requestID && !(id == "" && e.legacy)) {
		return "", otpErrExpired
	}
	if !b.start.Add(otpWindow).After(now) {
		b.start = now
		b.sends = 0
		b.failures = 0
	}
	if e.attempts >= otpMaxAttempts || b.failures >= otpMaxFailures {
		return "", otpErrTooMany
	}
	actual := crypto.HashToken(e.requestID + ":" + code)
	if subtle.ConstantTimeCompare([]byte(actual), []byte(e.codeHash)) != 1 {
		e.attempts++
		b.failures++
		return "", otpErrInvalid
	}
	delete(o.m, key)
	return e.device, nil
}
