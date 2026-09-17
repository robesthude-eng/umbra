package store

import (
	"errors"
	"time"
)

// OTPPolicy — лимиты отправки кодов. Значения задаёт httpapi, хранилище
// только применяет их атомарно (иначе два инстанса сервера обошли бы кулдаун).
type OTPPolicy struct {
	Window      time.Duration
	Cooldown    time.Duration
	MaxSends    int
	MaxFailures int
	// MaxPhones — предохранитель от разрастания таблицы бюджетов;
	// 0 означает «без ограничения».
	MaxPhones int
}

var (
	// ErrOTPThrottled — кулдаун или исчерпан бюджет окна.
	ErrOTPThrottled = errors.New("store: otp throttled")
	// ErrOTPCapacity — слишком много номеров в окне (защита хранилища).
	ErrOTPCapacity = errors.New("store: otp capacity")
)
