package httpapi

import (
	"context"
	"crypto/subtle"
	"errors"
	"time"

	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

const (
	otpCooldown    = time.Minute
	otpWindow      = 30 * time.Minute
	otpMaxSends    = 5
	otpMaxFailures = 10
	// otpMaxPhones защищает таблицу бюджетов от разрастания в пределах окна.
	otpMaxPhones = 4096
)

var (
	otpErrExpired  = errors.New("код не найден или истёк")
	otpErrTooMany  = errors.New("слишком много попыток")
	otpErrInvalid  = errors.New("неверный код")
	otpErrCapacity = errors.New("слишком много запросов")
)

// otpStore — тонкая обёртка над Store (v0.19). Состояние кодов и бюджетов
// больше не живёт в памяти процесса: код переживает рестарт, а лимиты
// работают сразу на нескольких инстансах сервера.
type otpStore struct {
	store store.Store
	now   func() time.Time
}

func newOTPStore(st store.Store) *otpStore {
	return &otpStore{store: st, now: time.Now}
}

func (o *otpStore) policy() store.OTPPolicy {
	return store.OTPPolicy{
		Window:      otpWindow,
		Cooldown:    otpCooldown,
		MaxSends:    otpMaxSends,
		MaxFailures: otpMaxFailures,
		MaxPhones:   otpMaxPhones,
	}
}

func otpCodeHash(requestID, code string) string {
	return crypto.HashToken(requestID + ":" + code)
}

// issue резервирует отправку в бюджете и сохраняет новый код.
// При отказе возвращает время до следующей попытки.
func (o *otpStore) issue(ctx context.Context, phone, purpose, binding, device, code string, legacy bool) (string, time.Duration, error) {
	now := o.now().UTC()
	retry, err := o.store.ReserveOTPSend(ctx, phone, now, o.policy())
	if err != nil {
		switch {
		case errors.Is(err, store.ErrOTPCapacity):
			if retry <= 0 {
				retry = otpCooldown
			}
			return "", retry, otpErrCapacity
		case errors.Is(err, store.ErrOTPThrottled):
			if retry <= 0 {
				retry = otpCooldown
			}
			return "", retry, otpErrTooMany
		default:
			return "", 0, err
		}
	}
	id, err := crypto.NewToken()
	if err != nil {
		return "", 0, err
	}
	record := &model.OTPCode{
		Phone:     phone,
		Purpose:   purpose,
		RequestID: id,
		CodeHash:  otpCodeHash(id, code),
		Binding:   binding,
		Device:    device,
		Legacy:    legacy,
		CreatedAt: now,
		ExpiresAt: now.Add(otpTTL),
	}
	if err := o.store.SaveOTPCode(ctx, record); err != nil {
		return "", 0, err
	}
	return id, 0, nil
}

// invalidate удаляет код (например, если доставка не удалась), но не возвращает
// израсходованную отправку в бюджет: иначе кулдаун можно было бы обнулять.
func (o *otpStore) invalidate(ctx context.Context, phone, purpose, id string) {
	_ = o.store.DeleteOTPCode(ctx, phone, purpose, id)
}

// verify проверяет код и при успехе гасит его (одноразовость).
func (o *otpStore) verify(ctx context.Context, phone, purpose, binding, id, code string) (string, error) {
	now := o.now().UTC()
	record, budget, err := o.store.LoadOTPCode(ctx, phone, purpose)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return "", otpErrExpired
		}
		return "", err
	}
	if !record.ExpiresAt.After(now) {
		o.invalidate(ctx, phone, purpose, record.RequestID)
		return "", otpErrExpired
	}
	if record.Binding != binding || (id != record.RequestID && !(id == "" && record.Legacy)) {
		return "", otpErrExpired
	}
	failures := 0
	if budget != nil && budget.WindowStart.Add(otpWindow).After(now) {
		failures = budget.Failures
	}
	if record.Attempts >= otpMaxAttempts || failures >= otpMaxFailures {
		return "", otpErrTooMany
	}
	actual := otpCodeHash(record.RequestID, code)
	if subtle.ConstantTimeCompare([]byte(actual), []byte(record.CodeHash)) != 1 {
		if err := o.store.FailOTPAttempt(ctx, phone, purpose, record.RequestID, now, otpWindow); err != nil && !errors.Is(err, store.ErrNotFound) {
			return "", err
		}
		return "", otpErrInvalid
	}
	if err := o.store.ConsumeOTPCode(ctx, phone, purpose, record.RequestID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			// Код уже использован параллельным запросом — второй раз не пускаем.
			return "", otpErrExpired
		}
		return "", err
	}
	return record.Device, nil
}
