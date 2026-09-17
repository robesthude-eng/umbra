package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

	"umbra/server/internal/model"
)

// PostgreSQL-реализация OTP-состояния и инвайт-кодов (v0.19).
// Счётчики меняются в транзакции с SELECT ... FOR UPDATE, иначе несколько
// инстансов сервера обошли бы кулдаун и лимит попыток.

func (p *PostgresStore) ReserveOTPSend(ctx context.Context, phone string, now time.Time, policy OTPPolicy) (time.Duration, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback(ctx)

	var (
		windowStart time.Time
		lastSent    time.Time
		sends       int
		failures    int
		found       = true
	)
	err = tx.QueryRow(ctx,
		`SELECT window_start, last_sent_at, sends, failures FROM otp_budgets WHERE phone = $1 FOR UPDATE`, phone).
		Scan(&windowStart, &lastSent, &sends, &failures)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			return 0, mapErr(err)
		}
		found = false
	}
	if !found {
		if policy.MaxPhones > 0 {
			var active int
			if err := tx.QueryRow(ctx,
				`SELECT count(*) FROM otp_budgets WHERE window_start > $1`, now.Add(-policy.Window)).Scan(&active); err != nil {
				return 0, mapErr(err)
			}
			if active >= policy.MaxPhones {
				return policy.Cooldown, ErrOTPCapacity
			}
		}
		windowStart, lastSent, sends, failures = now, time.Time{}, 0, 0
	}
	if !windowStart.Add(policy.Window).After(now) {
		windowStart, sends, failures = now, 0, 0
	}
	if (policy.MaxFailures > 0 && failures >= policy.MaxFailures) || (policy.MaxSends > 0 && sends >= policy.MaxSends) {
		return windowStart.Add(policy.Window).Sub(now), ErrOTPThrottled
	}
	if retry := lastSent.Add(policy.Cooldown).Sub(now); retry > 0 {
		return retry, ErrOTPThrottled
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO otp_budgets (phone, window_start, last_sent_at, sends, failures)
		 VALUES ($1,$2,$3,$4,$5)
		 ON CONFLICT (phone) DO UPDATE SET window_start = EXCLUDED.window_start, last_sent_at = EXCLUDED.last_sent_at, sends = EXCLUDED.sends, failures = EXCLUDED.failures`,
		phone, windowStart, now, sends+1, failures); err != nil {
		return 0, mapErr(err)
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, mapErr(err)
	}
	return 0, nil
}

func (p *PostgresStore) SaveOTPCode(ctx context.Context, code *model.OTPCode) error {
	if code == nil {
		return ErrNotFound
	}
	_, err := p.pool.Exec(ctx,
		`INSERT INTO otp_codes (phone, purpose, request_id, code_hash, binding, device, legacy, attempts, created_at, expires_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,0,$8,$9)
		 ON CONFLICT (phone, purpose) DO UPDATE SET request_id = EXCLUDED.request_id, code_hash = EXCLUDED.code_hash, binding = EXCLUDED.binding, device = EXCLUDED.device, legacy = EXCLUDED.legacy, attempts = 0, created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at`,
		code.Phone, code.Purpose, code.RequestID, code.CodeHash, code.Binding, code.Device, code.Legacy, code.CreatedAt, code.ExpiresAt)
	return mapErr(err)
}

func (p *PostgresStore) LoadOTPCode(ctx context.Context, phone, purpose string) (*model.OTPCode, *model.OTPBudget, error) {
	var c model.OTPCode
	c.Phone, c.Purpose = phone, purpose
	err := p.pool.QueryRow(ctx,
		`SELECT request_id, code_hash, binding, device, legacy, attempts, created_at, expires_at FROM otp_codes WHERE phone = $1 AND purpose = $2`,
		phone, purpose).Scan(&c.RequestID, &c.CodeHash, &c.Binding, &c.Device, &c.Legacy, &c.Attempts, &c.CreatedAt, &c.ExpiresAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil, ErrNotFound
		}
		return nil, nil, mapErr(err)
	}
	b := &model.OTPBudget{Phone: phone}
	err = p.pool.QueryRow(ctx,
		`SELECT window_start, last_sent_at, sends, failures FROM otp_budgets WHERE phone = $1`, phone).
		Scan(&b.WindowStart, &b.LastSentAt, &b.Sends, &b.Failures)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return &c, nil, nil
		}
		return nil, nil, mapErr(err)
	}
	return &c, b, nil
}

func (p *PostgresStore) FailOTPAttempt(ctx context.Context, phone, purpose, requestID string, now time.Time, window time.Duration) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	tag, err := tx.Exec(ctx,
		`UPDATE otp_codes SET attempts = attempts + 1 WHERE phone = $1 AND purpose = $2 AND request_id = $3`,
		phone, purpose, requestID)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO otp_budgets (phone, window_start, last_sent_at, sends, failures)
		 VALUES ($1,$2,to_timestamp(0),0,1)
		 ON CONFLICT (phone) DO UPDATE SET
		   window_start = CASE WHEN otp_budgets.window_start + $3::interval <= $2 THEN $2 ELSE otp_budgets.window_start END,
		   sends = CASE WHEN otp_budgets.window_start + $3::interval <= $2 THEN 0 ELSE otp_budgets.sends END,
		   failures = CASE WHEN otp_budgets.window_start + $3::interval <= $2 THEN 1 ELSE otp_budgets.failures + 1 END`,
		phone, now, window.String()); err != nil {
		return mapErr(err)
	}
	return mapErr(tx.Commit(ctx))
}

func (p *PostgresStore) ConsumeOTPCode(ctx context.Context, phone, purpose, requestID string) error {
	tag, err := p.pool.Exec(ctx,
		`DELETE FROM otp_codes WHERE phone = $1 AND purpose = $2 AND request_id = $3`, phone, purpose, requestID)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

func (p *PostgresStore) DeleteOTPCode(ctx context.Context, phone, purpose, requestID string) error {
	var err error
	if requestID == "" {
		_, err = p.pool.Exec(ctx, `DELETE FROM otp_codes WHERE phone = $1 AND purpose = $2`, phone, purpose)
	} else {
		_, err = p.pool.Exec(ctx, `DELETE FROM otp_codes WHERE phone = $1 AND purpose = $2 AND request_id = $3`, phone, purpose, requestID)
	}
	return mapErr(err)
}

func (p *PostgresStore) PurgeOTPState(ctx context.Context, now time.Time, window time.Duration) error {
	if _, err := p.pool.Exec(ctx, `DELETE FROM otp_codes WHERE expires_at <= $1`, now); err != nil {
		return mapErr(err)
	}
	_, err := p.pool.Exec(ctx,
		`DELETE FROM otp_budgets b
		  WHERE b.window_start + $2::interval <= $1
		    AND NOT EXISTS (SELECT 1 FROM otp_codes c WHERE c.phone = b.phone)`,
		now, window.String())
	return mapErr(err)
}

func (p *PostgresStore) CreateInvite(ctx context.Context, inv *model.Invite) error {
	if inv == nil {
		return ErrNotFound
	}
	_, err := p.pool.Exec(ctx,
		`INSERT INTO invite_codes (id, owner_id, code_hash, label, max_uses, uses, created_at, expires_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
		inv.ID, inv.OwnerID, inv.CodeHash, inv.Label, inv.MaxUses, inv.Uses, inv.CreatedAt, inv.ExpiresAt)
	return mapErr(err)
}

func (p *PostgresStore) ListInvites(ctx context.Context, ownerID string) ([]model.Invite, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT id, owner_id, code_hash, label, max_uses, uses, created_at, expires_at, revoked_at
		   FROM invite_codes WHERE owner_id = $1 ORDER BY created_at DESC, id`, ownerID)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	out := []model.Invite{}
	for rows.Next() {
		var inv model.Invite
		if err := rows.Scan(&inv.ID, &inv.OwnerID, &inv.CodeHash, &inv.Label, &inv.MaxUses, &inv.Uses, &inv.CreatedAt, &inv.ExpiresAt, &inv.RevokedAt); err != nil {
			return nil, mapErr(err)
		}
		out = append(out, inv)
	}
	if err := rows.Err(); err != nil {
		return nil, mapErr(err)
	}
	sortInvites(out)
	return out, nil
}

func (p *PostgresStore) RevokeInvite(ctx context.Context, ownerID, id string) error {
	tag, err := p.pool.Exec(ctx,
		`UPDATE invite_codes SET revoked_at = now() WHERE id = $1 AND owner_id = $2 AND revoked_at IS NULL`, id, ownerID)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		// Уже отозван — операция идемпотентна; чужой/несуществующий — ErrNotFound.
		var exists bool
		if err := p.pool.QueryRow(ctx,
			`SELECT EXISTS (SELECT 1 FROM invite_codes WHERE id = $1 AND owner_id = $2)`, id, ownerID).Scan(&exists); err != nil {
			return mapErr(err)
		}
		if !exists {
			return ErrNotFound
		}
	}
	return nil
}

func (p *PostgresStore) GetInviteByHash(ctx context.Context, codeHash string, now time.Time) (*model.Invite, error) {
	var inv model.Invite
	err := p.pool.QueryRow(ctx,
		`SELECT id, owner_id, code_hash, label, max_uses, uses, created_at, expires_at, revoked_at
		   FROM invite_codes WHERE code_hash = $1`, codeHash).
		Scan(&inv.ID, &inv.OwnerID, &inv.CodeHash, &inv.Label, &inv.MaxUses, &inv.Uses, &inv.CreatedAt, &inv.ExpiresAt, &inv.RevokedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, mapErr(err)
	}
	if !inv.Active(now) {
		return nil, ErrNotFound
	}
	return &inv, nil
}

func (p *PostgresStore) ClaimInvite(ctx context.Context, codeHash, phoneHash, userID string, now time.Time) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var inv model.Invite
	err = tx.QueryRow(ctx,
		`SELECT id, owner_id, code_hash, label, max_uses, uses, created_at, expires_at, revoked_at
		   FROM invite_codes WHERE code_hash = $1 FOR UPDATE`, codeHash).
		Scan(&inv.ID, &inv.OwnerID, &inv.CodeHash, &inv.Label, &inv.MaxUses, &inv.Uses, &inv.CreatedAt, &inv.ExpiresAt, &inv.RevokedAt)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return mapErr(err)
	}
	var used bool
	if err := tx.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM invite_uses WHERE invite_id = $1 AND phone_hash = $2)`, inv.ID, phoneHash).Scan(&used); err != nil {
		return mapErr(err)
	}
	if used {
		if userID != "" {
			if _, err := tx.Exec(ctx,
				`UPDATE invite_uses SET user_id = COALESCE(user_id, $3) WHERE invite_id = $1 AND phone_hash = $2`,
				inv.ID, phoneHash, userID); err != nil {
				return mapErr(err)
			}
		}
		return mapErr(tx.Commit(ctx))
	}
	if !inv.Active(now) {
		return ErrNotFound
	}
	if _, err := tx.Exec(ctx, `UPDATE invite_codes SET uses = uses + 1 WHERE id = $1`, inv.ID); err != nil {
		return mapErr(err)
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO invite_uses (invite_id, phone_hash, user_id, used_at) VALUES ($1,$2,$3,$4)
		 ON CONFLICT (invite_id, phone_hash) DO NOTHING`,
		inv.ID, phoneHash, nullIfEmpty(userID), now); err != nil {
		return mapErr(err)
	}
	return mapErr(tx.Commit(ctx))
}

func (p *PostgresStore) InviteUses(ctx context.Context, ownerID, id string) ([]InviteUse, error) {
	var exists bool
	if err := p.pool.QueryRow(ctx,
		`SELECT EXISTS (SELECT 1 FROM invite_codes WHERE id = $1 AND owner_id = $2)`, id, ownerID).Scan(&exists); err != nil {
		return nil, mapErr(err)
	}
	if !exists {
		return nil, ErrNotFound
	}
	rows, err := p.pool.Query(ctx,
		`SELECT phone_hash, COALESCE(user_id,''), used_at FROM invite_uses WHERE invite_id = $1 ORDER BY used_at DESC`, id)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()
	out := []InviteUse{}
	for rows.Next() {
		var u InviteUse
		if err := rows.Scan(&u.PhoneHash, &u.UserID, &u.UsedAt); err != nil {
			return nil, mapErr(err)
		}
		out = append(out, u)
	}
	if err := rows.Err(); err != nil {
		return nil, mapErr(err)
	}
	sortInviteUses(out)
	return out, nil
}
