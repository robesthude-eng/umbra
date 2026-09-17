package store

import (
	"context"
	"time"

	"umbra/server/internal/model"
)

// In-memory реализация OTP-состояния и инвайт-кодов (v0.19).
// Поведение должно совпадать с PostgresStore.
//
// В internal/store/memory.go в структуру MemoryStore добавлены поля:
//
//	otpCodes   map[string]*model.OTPCode
//	otpBudgets map[string]*model.OTPBudget
//	invites    map[string]*model.Invite
//	inviteUses map[string][]InviteUse

func otpKey(phone, purpose string) string { return phone + ":" + purpose }

func (m *MemoryStore) initOTP() {
	if m.otpCodes == nil {
		m.otpCodes = make(map[string]*model.OTPCode)
	}
	if m.otpBudgets == nil {
		m.otpBudgets = make(map[string]*model.OTPBudget)
	}
}

func (m *MemoryStore) initInvites() {
	if m.invites == nil {
		m.invites = make(map[string]*model.Invite)
	}
	if m.inviteUses == nil {
		m.inviteUses = make(map[string][]InviteUse)
	}
}

// sweepOTPLocked удаляет истёкшие коды и остывшие бюджеты.
// Бюджет живёт, пока есть хотя бы один живой код по этому номеру.
func (m *MemoryStore) sweepOTPLocked(now time.Time, window time.Duration) {
	m.initOTP()
	live := make(map[string]bool)
	for k, c := range m.otpCodes {
		if !c.ExpiresAt.After(now) {
			delete(m.otpCodes, k)
			continue
		}
		live[c.Phone] = true
	}
	for phone, b := range m.otpBudgets {
		if live[phone] {
			continue
		}
		if b.WindowStart.Add(window).After(now) {
			continue
		}
		delete(m.otpBudgets, phone)
	}
}

func (m *MemoryStore) ReserveOTPSend(_ context.Context, phone string, now time.Time, policy OTPPolicy) (time.Duration, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sweepOTPLocked(now, policy.Window)
	b := m.otpBudgets[phone]
	if b == nil {
		if policy.MaxPhones > 0 && len(m.otpBudgets) >= policy.MaxPhones {
			return policy.Cooldown, ErrOTPCapacity
		}
		b = &model.OTPBudget{Phone: phone, WindowStart: now}
		m.otpBudgets[phone] = b
	}
	if !b.WindowStart.Add(policy.Window).After(now) {
		b.WindowStart = now
		b.Sends = 0
		b.Failures = 0
	}
	if (policy.MaxFailures > 0 && b.Failures >= policy.MaxFailures) || (policy.MaxSends > 0 && b.Sends >= policy.MaxSends) {
		return b.WindowStart.Add(policy.Window).Sub(now), ErrOTPThrottled
	}
	if retry := b.LastSentAt.Add(policy.Cooldown).Sub(now); retry > 0 {
		return retry, ErrOTPThrottled
	}
	b.Sends++
	b.LastSentAt = now
	return 0, nil
}

func (m *MemoryStore) SaveOTPCode(_ context.Context, code *model.OTPCode) error {
	if code == nil {
		return ErrNotFound
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initOTP()
	cp := *code
	m.otpCodes[otpKey(code.Phone, code.Purpose)] = &cp
	return nil
}

func (m *MemoryStore) LoadOTPCode(_ context.Context, phone, purpose string) (*model.OTPCode, *model.OTPBudget, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initOTP()
	c := m.otpCodes[otpKey(phone, purpose)]
	if c == nil {
		return nil, nil, ErrNotFound
	}
	code := *c
	var budget *model.OTPBudget
	if b := m.otpBudgets[phone]; b != nil {
		cp := *b
		budget = &cp
	}
	return &code, budget, nil
}

func (m *MemoryStore) FailOTPAttempt(_ context.Context, phone, purpose, requestID string, now time.Time, window time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initOTP()
	c := m.otpCodes[otpKey(phone, purpose)]
	if c == nil || c.RequestID != requestID {
		return ErrNotFound
	}
	c.Attempts++
	b := m.otpBudgets[phone]
	if b == nil {
		b = &model.OTPBudget{Phone: phone, WindowStart: now}
		m.otpBudgets[phone] = b
	}
	if !b.WindowStart.Add(window).After(now) {
		b.WindowStart = now
		b.Sends = 0
		b.Failures = 0
	}
	b.Failures++
	return nil
}

func (m *MemoryStore) ConsumeOTPCode(_ context.Context, phone, purpose, requestID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initOTP()
	key := otpKey(phone, purpose)
	c := m.otpCodes[key]
	if c == nil || c.RequestID != requestID {
		return ErrNotFound
	}
	delete(m.otpCodes, key)
	return nil
}

func (m *MemoryStore) DeleteOTPCode(_ context.Context, phone, purpose, requestID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initOTP()
	key := otpKey(phone, purpose)
	c := m.otpCodes[key]
	if c == nil {
		return nil
	}
	if requestID != "" && c.RequestID != requestID {
		return nil
	}
	delete(m.otpCodes, key)
	return nil
}

func (m *MemoryStore) PurgeOTPState(_ context.Context, now time.Time, window time.Duration) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.sweepOTPLocked(now, window)
	return nil
}

func (m *MemoryStore) CreateInvite(_ context.Context, inv *model.Invite) error {
	if inv == nil {
		return ErrNotFound
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initInvites()
	if _, ok := m.users[inv.OwnerID]; !ok {
		return ErrNotFound
	}
	if _, ok := m.invites[inv.ID]; ok {
		return ErrConflict
	}
	for _, cur := range m.invites {
		if cur.CodeHash == inv.CodeHash {
			return ErrConflict
		}
	}
	cp := *inv
	m.invites[inv.ID] = &cp
	return nil
}

func (m *MemoryStore) ListInvites(_ context.Context, ownerID string) ([]model.Invite, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initInvites()
	out := make([]model.Invite, 0, len(m.invites))
	for _, inv := range m.invites {
		if inv.OwnerID != ownerID {
			continue
		}
		out = append(out, *inv)
	}
	sortInvites(out)
	return out, nil
}

func (m *MemoryStore) RevokeInvite(_ context.Context, ownerID, id string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initInvites()
	inv := m.invites[id]
	if inv == nil || inv.OwnerID != ownerID {
		return ErrNotFound
	}
	if inv.RevokedAt != nil {
		return nil
	}
	now := time.Now().UTC()
	inv.RevokedAt = &now
	return nil
}

func (m *MemoryStore) GetInviteByHash(_ context.Context, codeHash string, now time.Time) (*model.Invite, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initInvites()
	for _, inv := range m.invites {
		if inv.CodeHash != codeHash {
			continue
		}
		if !inv.Active(now) {
			return nil, ErrNotFound
		}
		cp := *inv
		return &cp, nil
	}
	return nil, ErrNotFound
}

// ClaimInvite атомарно увеличивает счётчик. Повторный вход того же номера
// не тратит новое использование.
func (m *MemoryStore) ClaimInvite(_ context.Context, codeHash, phoneHash, userID string, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initInvites()
	for _, inv := range m.invites {
		if inv.CodeHash != codeHash {
			continue
		}
		for i, use := range m.inviteUses[inv.ID] {
			if use.PhoneHash == phoneHash {
				if use.UserID == "" && userID != "" {
					m.inviteUses[inv.ID][i].UserID = userID
				}
				return nil
			}
		}
		if !inv.Active(now) {
			return ErrNotFound
		}
		inv.Uses++
		m.inviteUses[inv.ID] = append(m.inviteUses[inv.ID], InviteUse{PhoneHash: phoneHash, UserID: userID, UsedAt: now})
		return nil
	}
	return ErrNotFound
}

func (m *MemoryStore) InviteUses(_ context.Context, ownerID, id string) ([]InviteUse, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.initInvites()
	inv := m.invites[id]
	if inv == nil || inv.OwnerID != ownerID {
		return nil, ErrNotFound
	}
	out := make([]InviteUse, len(m.inviteUses[id]))
	copy(out, m.inviteUses[id])
	sortInviteUses(out)
	return out, nil
}
