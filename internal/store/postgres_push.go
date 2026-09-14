package store

import (
	"context"

	"umbra/server/internal/model"
)

// SavePushDevice — идемпотентная привязка токена FCM к пользователю.
// Токен — первичный ключ, поэтому повторная регистрация просто обновляет владельца.
func (p *PostgresStore) SavePushDevice(ctx context.Context, userID, token, platform string) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO push_devices (token, user_id, platform, updated_at)
		VALUES ($1, $2, $3, now())
		ON CONFLICT (token) DO UPDATE
		SET user_id = EXCLUDED.user_id, platform = EXCLUDED.platform, updated_at = now()`,
		token, userID, platform)
	return err
}

// ListPushDevices — все устройства пользователя, свежие первыми.
func (p *PostgresStore) ListPushDevices(ctx context.Context, userID string) ([]model.PushDevice, error) {
	rows, err := p.pool.Query(ctx, `SELECT d.token,d.user_id,d.platform,d.updated_at,COALESCE(d.session_id,'') FROM push_devices d LEFT JOIN auth_tokens a ON a.session_id=d.session_id WHERE d.user_id=$1 AND (d.session_id IS NULL OR (a.expires_at>now() AND a.created_at>now()-interval '2160 hours')) ORDER BY d.updated_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []model.PushDevice
	for rows.Next() {
		var d model.PushDevice
		if err := rows.Scan(&d.Token, &d.UserID, &d.Platform, &d.UpdatedAt, &d.SessionID); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// DeletePushDevice убирает токен при выходе из аккаунта или когда FCM
// сообщает, что устройство больше не принимает уведомления.
func (p *PostgresStore) DeletePushDevice(ctx context.Context, token string) error {
	tag, err := p.pool.Exec(ctx, `DELETE FROM push_devices WHERE token = $1`, token)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
