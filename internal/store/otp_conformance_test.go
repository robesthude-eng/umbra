package store

import (
	"context"
	"errors"
	"fmt"
	"testing"
	"time"

	"umbra/server/internal/model"
)

// Контракт OTP-состояния и инвайт-кодов (0.19) для обеих реализаций Store.
// Без этих кейсов кулдаун, однократность кода и списание инвайтов проверялись
// только на MemoryStore через HTTP-тесты, а расхождения с PostgreSQL
// (транзакции, FOR UPDATE, ON CONFLICT) всплыли бы уже на бою.

func TestMemoryStoreOTPConformance(t *testing.T) {
	otpContract(t, NewMemoryStore())
}

func TestPostgresStoreOTPConformance(t *testing.T) {
	st, done := newPostgresContractStore(t, "umbra_otp_conformance")
	defer done()
	otpContract(t, st)
}

func otpTestPolicy() OTPPolicy {
	return OTPPolicy{
		Window:      30 * time.Minute,
		Cooldown:    time.Minute,
		MaxSends:    5,
		MaxFailures: 10,
		MaxPhones:   8,
	}
}

func otpContract(t *testing.T, st Store) {
	t.Helper()
	ctx := context.Background()
	base := time.Now().UTC().Truncate(time.Millisecond)
	policy := otpTestPolicy()

	t.Run("send budget", func(t *testing.T) {
		const phone = "+70000000001"
		if _, err := st.ReserveOTPSend(ctx, phone, base, policy); err != nil {
			t.Fatalf("первая отправка: %v", err)
		}
		retry, err := st.ReserveOTPSend(ctx, phone, base.Add(10*time.Second), policy)
		if !errors.Is(err, ErrOTPThrottled) {
			t.Fatalf("ожидался ErrOTPThrottled, получен %v", err)
		}
		if retry <= 0 {
			t.Fatalf("retry должен быть положительным, получен %v", retry)
		}
		for i := 1; i < policy.MaxSends; i++ {
			at := base.Add(time.Duration(i) * 2 * time.Minute)
			if _, err := st.ReserveOTPSend(ctx, phone, at, policy); err != nil {
				t.Fatalf("отправка %d: %v", i+1, err)
			}
		}
		if _, err := st.ReserveOTPSend(ctx, phone, base.Add(20*time.Minute), policy); !errors.Is(err, ErrOTPThrottled) {
			t.Fatalf("бюджет отправок не сработал: %v", err)
		}
		if _, err := st.ReserveOTPSend(ctx, phone, base.Add(policy.Window+time.Minute), policy); err != nil {
			t.Fatalf("после окна отправка должна быть разрешена: %v", err)
		}
	})

	t.Run("capacity", func(t *testing.T) {
		small := policy
		small.MaxPhones = 4
		at := base.Add(3 * policy.Window)
		for i := 0; i < small.MaxPhones; i++ {
			phone := fmt.Sprintf("+7100000%04d", i)
			if _, err := st.ReserveOTPSend(ctx, phone, at, small); err != nil {
				t.Fatalf("номер %d: %v", i, err)
			}
		}
		if _, err := st.ReserveOTPSend(ctx, "+79999999999", at, small); !errors.Is(err, ErrOTPCapacity) {
			t.Fatalf("ожидался ErrOTPCapacity, получен %v", err)
		}
		if _, err := st.ReserveOTPSend(ctx, "+79999999999", at.Add(small.Window+time.Minute), small); err != nil {
			t.Fatalf("после окна емкость не освободилась: %v", err)
		}
	})

	t.Run("code lifecycle", func(t *testing.T) {
		const phone = "+70000000002"
		at := base.Add(3 * policy.Window)
		if _, err := st.ReserveOTPSend(ctx, phone, at, policy); err != nil {
			t.Fatalf("резерв: %v", err)
		}
		if _, _, err := st.LoadOTPCode(ctx, phone, "login"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("до записи кода ожидался ErrNotFound, получен %v", err)
		}
		first := &model.OTPCode{
			Phone:     phone,
			Purpose:   "login",
			RequestID: "req-1",
			CodeHash:  "hash-1",
			Device:    "Pixel",
			CreatedAt: at,
			ExpiresAt: at.Add(5 * time.Minute),
		}
		if err := st.SaveOTPCode(ctx, first); err != nil {
			t.Fatalf("SaveOTPCode: %v", err)
		}
		got, budget, err := st.LoadOTPCode(ctx, phone, "login")
		if err != nil {
			t.Fatalf("LoadOTPCode: %v", err)
		}
		if got.RequestID != "req-1" || got.CodeHash != "hash-1" || got.Device != "Pixel" {
			t.Fatalf("код прочитан неверно: %+v", got)
		}
		if budget == nil || budget.Sends == 0 {
			t.Fatalf("бюджет должен возвращаться рядом с кодом: %+v", budget)
		}

		second := &model.OTPCode{
			Phone:     phone,
			Purpose:   "login",
			RequestID: "req-2",
			CodeHash:  "hash-2",
			CreatedAt: at,
			ExpiresAt: at.Add(5 * time.Minute),
		}
		if err := st.SaveOTPCode(ctx, second); err != nil {
			t.Fatalf("перезапись кода: %v", err)
		}
		got, _, err = st.LoadOTPCode(ctx, phone, "login")
		if err != nil || got.RequestID != "req-2" {
			t.Fatalf("ожидался req-2: %v %+v", err, got)
		}

		if err := st.FailOTPAttempt(ctx, phone, "login", "req-2", at, policy.Window); err != nil {
			t.Fatalf("FailOTPAttempt: %v", err)
		}
		got, budget, err = st.LoadOTPCode(ctx, phone, "login")
		if err != nil {
			t.Fatalf("LoadOTPCode после ошибки: %v", err)
		}
		if got.Attempts != 1 {
			t.Fatalf("attempts = %d, ожидалось 1", got.Attempts)
		}
		if budget == nil || budget.Failures != 1 {
			t.Fatalf("failures: %+v, ожидалось 1", budget)
		}
		if err := st.FailOTPAttempt(ctx, phone, "login", "req-1", at, policy.Window); !errors.Is(err, ErrNotFound) {
			t.Fatalf("чужой request_id: ожидался ErrNotFound, получен %v", err)
		}

		if err := st.ConsumeOTPCode(ctx, phone, "login", "req-2"); err != nil {
			t.Fatalf("ConsumeOTPCode: %v", err)
		}
		if err := st.ConsumeOTPCode(ctx, phone, "login", "req-2"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("повторное гашение: ожидался ErrNotFound, получен %v", err)
		}
		if _, _, err := st.LoadOTPCode(ctx, phone, "login"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("погашенный код все еще читается: %v", err)
		}
	})

	t.Run("delete and purge", func(t *testing.T) {
		const phone = "+70000000003"
		const livePhone = "+70000000004"
		at := base.Add(4 * policy.Window)
		if _, err := st.ReserveOTPSend(ctx, phone, at, policy); err != nil {
			t.Fatalf("резерв: %v", err)
		}
		code := &model.OTPCode{
			Phone:     phone,
			Purpose:   "delete_account",
			RequestID: "req-d",
			CodeHash:  "hash-d",
			Binding:   "session-a",
			CreatedAt: at,
			ExpiresAt: at.Add(5 * time.Minute),
		}
		if err := st.SaveOTPCode(ctx, code); err != nil {
			t.Fatalf("SaveOTPCode: %v", err)
		}
		if err := st.DeleteOTPCode(ctx, phone, "delete_account", "req-d"); err != nil {
			t.Fatalf("DeleteOTPCode: %v", err)
		}
		if _, _, err := st.LoadOTPCode(ctx, phone, "delete_account"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("удаленный код читается: %v", err)
		}

		expired := &model.OTPCode{
			Phone:     phone,
			Purpose:   "login",
			RequestID: "req-old",
			CodeHash:  "hash-old",
			CreatedAt: at.Add(-time.Hour),
			ExpiresAt: at.Add(-30 * time.Minute),
		}
		if err := st.SaveOTPCode(ctx, expired); err != nil {
			t.Fatalf("SaveOTPCode(expired): %v", err)
		}
		live := &model.OTPCode{
			Phone:     livePhone,
			Purpose:   "login",
			RequestID: "req-live",
			CodeHash:  "hash-live",
			CreatedAt: at,
			ExpiresAt: at.Add(5 * time.Minute),
		}
		if err := st.SaveOTPCode(ctx, live); err != nil {
			t.Fatalf("SaveOTPCode(live): %v", err)
		}
		if err := st.PurgeOTPState(ctx, at, policy.Window); err != nil {
			t.Fatalf("PurgeOTPState: %v", err)
		}
		if _, _, err := st.LoadOTPCode(ctx, phone, "login"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("истекший код не удален: %v", err)
		}
		if _, _, err := st.LoadOTPCode(ctx, livePhone, "login"); err != nil {
			t.Fatalf("живой код удален: %v", err)
		}
	})

	t.Run("invites", func(t *testing.T) {
		owner := testUser("otp-invite-owner")
		stranger := testUser("otp-invite-stranger")
		for _, u := range []*model.User{owner, stranger} {
			if err := st.CreateUser(ctx, u); err != nil {
				t.Fatalf("CreateUser: %v", err)
			}
		}
		at := base.Add(5 * policy.Window)
		inv := &model.Invite{
			ID:        "inv-1",
			OwnerID:   owner.ID,
			CodeHash:  "invite-hash-1",
			Label:     "друг",
			MaxUses:   2,
			CreatedAt: at,
			ExpiresAt: at.Add(24 * time.Hour),
		}
		if err := st.CreateInvite(ctx, inv); err != nil {
			t.Fatalf("CreateInvite: %v", err)
		}
		dup := *inv
		dup.ID = "inv-dup"
		if err := st.CreateInvite(ctx, &dup); !errors.Is(err, ErrConflict) {
			t.Fatalf("дубль кода: ожидался ErrConflict, получен %v", err)
		}

		found, err := st.GetInviteByHash(ctx, "invite-hash-1", at)
		if err != nil || found == nil || found.ID != inv.ID {
			t.Fatalf("GetInviteByHash: %v %+v", err, found)
		}
		if _, err := st.GetInviteByHash(ctx, "invite-hash-1", at.Add(48*time.Hour)); !errors.Is(err, ErrNotFound) {
			t.Fatalf("истекший инвайт найден: %v", err)
		}

		if err := st.ClaimInvite(ctx, "invite-hash-1", "phone-hash-a", stranger.ID, at); err != nil {
			t.Fatalf("ClaimInvite: %v", err)
		}
		if err := st.ClaimInvite(ctx, "invite-hash-1", "phone-hash-a", stranger.ID, at.Add(time.Minute)); err != nil {
			t.Fatalf("повторный вход того же номера: %v", err)
		}
		uses, err := st.InviteUses(ctx, owner.ID, inv.ID)
		if err != nil {
			t.Fatalf("InviteUses: %v", err)
		}
		if len(uses) != 1 || uses[0].PhoneHash != "phone-hash-a" {
			t.Fatalf("повторный вход создал лишнее использование: %+v", uses)
		}

		if err := st.ClaimInvite(ctx, "invite-hash-1", "phone-hash-b", "", at.Add(2*time.Minute)); err != nil {
			t.Fatalf("второе списание: %v", err)
		}
		if err := st.ClaimInvite(ctx, "invite-hash-1", "phone-hash-c", "", at.Add(3*time.Minute)); !errors.Is(err, ErrNotFound) {
			t.Fatalf("исчерпанный инвайт все еще списывается: %v", err)
		}
		if _, err := st.GetInviteByHash(ctx, "invite-hash-1", at.Add(4*time.Minute)); !errors.Is(err, ErrNotFound) {
			t.Fatalf("исчерпанный инвайт активен: %v", err)
		}

		second := &model.Invite{
			ID:        "inv-2",
			OwnerID:   owner.ID,
			CodeHash:  "invite-hash-2",
			MaxUses:   1,
			CreatedAt: at,
			ExpiresAt: at.Add(24 * time.Hour),
		}
		if err := st.CreateInvite(ctx, second); err != nil {
			t.Fatalf("CreateInvite(2): %v", err)
		}
		list, err := st.ListInvites(ctx, owner.ID)
		if err != nil || len(list) != 2 {
			t.Fatalf("ListInvites: %v %d", err, len(list))
		}
		if other, err := st.ListInvites(ctx, stranger.ID); err != nil || len(other) != 0 {
			t.Fatalf("чужие инвайты видны: %v %+v", err, other)
		}
		if err := st.RevokeInvite(ctx, stranger.ID, second.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("чужой отзыв: ожидался ErrNotFound, получен %v", err)
		}
		if err := st.RevokeInvite(ctx, owner.ID, second.ID); err != nil {
			t.Fatalf("RevokeInvite: %v", err)
		}
		if _, err := st.GetInviteByHash(ctx, "invite-hash-2", at); !errors.Is(err, ErrNotFound) {
			t.Fatalf("отозванный инвайт активен: %v", err)
		}
		if _, err := st.InviteUses(ctx, stranger.ID, inv.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("чужие использования видны: %v", err)
		}
	})
}
