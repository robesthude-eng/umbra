package store

import (
	"bytes"
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"umbra/server/internal/model"
)

// PostgresStore — продакшн-хранилище на PostgreSQL (через pgx).
type PostgresStore struct {
	pool *pgxpool.Pool
}

func NewPostgresStore(ctx context.Context, dsn string) (*PostgresStore, error) {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return &PostgresStore{pool: pool}, nil
}

func (p *PostgresStore) CreateUser(ctx context.Context, u *model.User) error {
	if u.KeyVersion == 0 { u.KeyVersion = 1 }
	if u.RegistrationID == 0 { u.RegistrationID = 1 }
	if u.SignedPrekeyID == 0 { u.SignedPrekeyID = 1 }
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	_, err = tx.Exec(ctx,
		`INSERT INTO users (id, username, identity_ed25519, identity_x25519, signed_prekey, signed_prekey_sig, key_version, registration_id, signed_prekey_id, key_bundle_id)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,
		u.ID, u.Username, u.IdentityEd25519, u.IdentityX25519, u.SignedPrekey, u.SignedPrekeySig, u.KeyVersion, u.RegistrationID, u.SignedPrekeyID, u.KeyBundleID)
	if err != nil {
		return mapErr(err)
	}
	for _, pk := range u.OneTimePrekeys {
		if _, err = tx.Exec(ctx,
			`INSERT INTO one_time_prekeys (user_id, prekey) VALUES ($1,$2)`, u.ID, pk); err != nil {
			return mapErr(err)
		}
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) GetUserByUsername(ctx context.Context, username string) (*model.User, error) {
	var u model.User
	err := p.pool.QueryRow(ctx,
		`SELECT id, username, identity_ed25519, identity_x25519, signed_prekey, signed_prekey_sig, created_at, key_version, registration_id, signed_prekey_id, key_bundle_id
		 FROM users WHERE username = $1`, username).
		Scan(&u.ID, &u.Username, &u.IdentityEd25519, &u.IdentityX25519, &u.SignedPrekey, &u.SignedPrekeySig, &u.CreatedAt, &u.KeyVersion, &u.RegistrationID, &u.SignedPrekeyID, &u.KeyBundleID)
	if err != nil {
		return nil, mapErr(err)
	}
	return &u, nil
}

func (p *PostgresStore) GetUserByID(ctx context.Context, id string) (*model.User, error) {
	var u model.User
	err := p.pool.QueryRow(ctx,
		`SELECT id, username, identity_ed25519, identity_x25519, signed_prekey, signed_prekey_sig, created_at, key_version, registration_id, signed_prekey_id, key_bundle_id
		 FROM users WHERE id = $1`, id).
		Scan(&u.ID, &u.Username, &u.IdentityEd25519, &u.IdentityX25519, &u.SignedPrekey, &u.SignedPrekeySig, &u.CreatedAt, &u.KeyVersion, &u.RegistrationID, &u.SignedPrekeyID, &u.KeyBundleID)
	if err != nil {
		return nil, mapErr(err)
	}
	return &u, nil
}

func (p *PostgresStore) TakeOneTimePrekey(ctx context.Context, userID string) ([]byte, error) {
	var pk []byte
	err := p.pool.QueryRow(ctx,
		`DELETE FROM one_time_prekeys
		 WHERE id = (SELECT id FROM one_time_prekeys WHERE user_id = $1 ORDER BY id LIMIT 1)
		 RETURNING prekey`, userID).Scan(&pk)
	if err != nil {
		return nil, mapErr(err)
	}
	return pk, nil
}

func (p *PostgresStore) PutToken(ctx context.Context, tokenHash, userID string, expires time.Time) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO auth_tokens (token_hash, user_id, expires_at) VALUES ($1,$2,$3)`,
		tokenHash, userID, expires)
	return mapErr(err)
}

func (p *PostgresStore) GetUserIDByTokenHash(ctx context.Context, tokenHash string) (string, error) {
	var userID string
	err := p.pool.QueryRow(ctx,
		`SELECT user_id FROM auth_tokens WHERE token_hash = $1 AND expires_at > now()`, tokenHash).Scan(&userID)
	if err != nil {
		return "", mapErr(err)
	}
	return userID, nil
}

func (p *PostgresStore) DeleteToken(ctx context.Context, tokenHash string) error {
	_, err := p.pool.Exec(ctx, `DELETE FROM auth_tokens WHERE token_hash = $1`, tokenHash)
	return mapErr(err)
}

func (p *PostgresStore) SaveMessage(ctx context.Context, m *model.Message) error {
    // PostgreSQL сохраняет TIMESTAMPTZ с микросекундной точностью.
    m.CreatedAt = m.CreatedAt.Truncate(time.Microsecond)
    if m.ExpiresAt != nil { expires := m.ExpiresAt.Truncate(time.Microsecond); m.ExpiresAt = &expires }
	tx, err := p.pool.Begin(ctx)
	if err != nil { return err }
	defer tx.Rollback(ctx)
	if m.ClientID != "" {
		hash := messageRequestHash(m)
		tag, err := tx.Exec(ctx, `INSERT INTO message_receipts
		 (sender_id,client_id,request_hash,message_id,created_at,expires_at)
		 VALUES ($1,$2,$3,$4,$5,$6) ON CONFLICT (sender_id,client_id) DO NOTHING`,
		 m.SenderID, m.ClientID, hash[:], m.ID, m.CreatedAt, m.ExpiresAt)
		if err != nil { return mapErr(err) }
		if tag.RowsAffected() == 0 {
			var previous []byte
			err = tx.QueryRow(ctx, `SELECT request_hash,message_id,created_at,expires_at FROM message_receipts
			 WHERE sender_id=$1 AND client_id=$2`, m.SenderID, m.ClientID).
			 Scan(&previous, &m.ID, &m.CreatedAt, &m.ExpiresAt)
			if err != nil { return mapErr(err) }
			if !bytes.Equal(previous, hash[:]) { return ErrConflict }
			return tx.Commit(ctx)
		}
	}
	var recipient, chatID any
	if m.RecipientID != "" {
		recipient = m.RecipientID
	}
	if m.ChatID != "" {
		chatID = m.ChatID
	}
	_, err = tx.Exec(ctx,
		`INSERT INTO messages (id, sender_id, recipient_id, chat_id, ciphertext, expires_at, created_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7)`,
		m.ID, m.SenderID, recipient, chatID, m.Ciphertext, m.ExpiresAt, m.CreatedAt)
	if err != nil { return mapErr(err) }
	return tx.Commit(ctx)
}

func (p *PostgresStore) ListMessages(ctx context.Context, userID string, since time.Time) ([]*model.Message, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT id, sender_id, recipient_id, chat_id, ciphertext, created_at, expires_at
		 FROM messages
		 WHERE created_at > $2
		   AND (expires_at IS NULL OR expires_at > now())
		   AND (recipient_id = $1 OR chat_id IN (SELECT chat_id FROM chat_members WHERE user_id = $1))
		 ORDER BY created_at ASC`,
		userID, since)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	out := make([]*model.Message, 0)
	for rows.Next() {
		var m model.Message
		var recipient, chatID *string
		if err := rows.Scan(&m.ID, &m.SenderID, &recipient, &chatID, &m.Ciphertext, &m.CreatedAt, &m.ExpiresAt); err != nil {
			return nil, err
		}
		if recipient != nil {
			m.RecipientID = *recipient
		}
		if chatID != nil {
			m.ChatID = *chatID
		}
		out = append(out, &m)
	}
	return out, rows.Err()
}

func (p *PostgresStore) SaveMedia(ctx context.Context, m *model.Media) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO media (id, owner_id, content_type, size, created_at) VALUES ($1,$2,$3,$4,$5)`,
		m.ID, m.OwnerID, m.ContentType, m.Size, m.CreatedAt)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23503" { // нет пользователя-владельца
		return ErrNotFound
	}
	return mapErr(err)
}

func (p *PostgresStore) GetMedia(ctx context.Context, id string) (*model.Media, error) {
	var m model.Media
	err := p.pool.QueryRow(ctx,
		`SELECT id, owner_id, content_type, size, created_at FROM media WHERE id = $1`, id).
		Scan(&m.ID, &m.OwnerID, &m.ContentType, &m.Size, &m.CreatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	return &m, nil
}

func (p *PostgresStore) MediaBytesForUser(ctx context.Context, userID string) (int64, error) {
	var total int64
	err := p.pool.QueryRow(ctx,
		`SELECT COALESCE(SUM(size), 0) FROM media WHERE owner_id = $1`, userID).Scan(&total)
	if err != nil {
		return 0, mapErr(err)
	}
	return total, nil
}

// ---------- чаты ----------

func (p *PostgresStore) CreateChat(ctx context.Context, c *model.Chat) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	if _, err := tx.Exec(ctx,
		`INSERT INTO chats (id, type, title, created_by) VALUES ($1,$2,$3,$4)`,
		c.ID, string(c.Type), c.Title, c.CreatedBy); err != nil {
		return mapErr(err)
	}
	if _, err := tx.Exec(ctx,
		`INSERT INTO chat_members (chat_id, user_id, role) VALUES ($1,$2,$3)`,
		c.ID, c.CreatedBy, string(model.RoleOwner)); err != nil {
		return mapErr(err)
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) GetChat(ctx context.Context, chatID string) (*model.Chat, error) {
	var c model.Chat
	var typ string
	err := p.pool.QueryRow(ctx,
		`SELECT id, type, title, created_by, created_at FROM chats WHERE id = $1`, chatID).
		Scan(&c.ID, &typ, &c.Title, &c.CreatedBy, &c.CreatedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	c.Type = model.ChatType(typ)
	return &c, nil
}

func (p *PostgresStore) AddMember(ctx context.Context, chatID, userID string, role model.MemberRole) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO chat_members (chat_id, user_id, role) VALUES ($1,$2,$3)`,
		chatID, userID, string(role))
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) {
		if pgErr.Code == "23503" { // нет чата или пользователя
			return ErrNotFound
		}
	}
	return mapErr(err)
}

func (p *PostgresStore) RemoveMember(ctx context.Context, chatID, userID string) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var role string
	err = tx.QueryRow(ctx,
		`SELECT role FROM chat_members WHERE chat_id = $1 AND user_id = $2`,
		chatID, userID).Scan(&role)
	if err != nil {
		return mapErr(err)
	}
	if model.MemberRole(role) == model.RoleOwner {
		return ErrForbidden
	}
	tag, err := tx.Exec(ctx,
		`DELETE FROM chat_members WHERE chat_id = $1 AND user_id = $2`, chatID, userID)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) GetMember(ctx context.Context, chatID, userID string) (*model.ChatMember, error) {
	var m model.ChatMember
	var role string
	err := p.pool.QueryRow(ctx,
		`SELECT chat_id, user_id, role, joined_at FROM chat_members WHERE chat_id = $1 AND user_id = $2`,
		chatID, userID).Scan(&m.ChatID, &m.UserID, &role, &m.JoinedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	m.Role = model.MemberRole(role)
	return &m, nil
}

func (p *PostgresStore) ListMembers(ctx context.Context, chatID string) ([]*model.ChatMember, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT chat_id, user_id, role, joined_at FROM chat_members WHERE chat_id = $1 ORDER BY user_id ASC`,
		chatID)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	out := make([]*model.ChatMember, 0)
	for rows.Next() {
		var m model.ChatMember
		var role string
		if err := rows.Scan(&m.ChatID, &m.UserID, &role, &m.JoinedAt); err != nil {
			return nil, err
		}
		m.Role = model.MemberRole(role)
		out = append(out, &m)
	}
	return out, rows.Err()
}

func (p *PostgresStore) ListChatsForUser(ctx context.Context, userID string) ([]*model.Chat, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT c.id, c.type, c.title, c.created_by, c.created_at
		 FROM chats c JOIN chat_members m ON c.id = m.chat_id
		 WHERE m.user_id = $1 ORDER BY c.created_at ASC`, userID)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	out := make([]*model.Chat, 0)
	for rows.Next() {
		var c model.Chat
		var typ string
		if err := rows.Scan(&c.ID, &typ, &c.Title, &c.CreatedBy, &c.CreatedAt); err != nil {
			return nil, err
		}
		c.Type = model.ChatType(typ)
		out = append(out, &c)
	}
	return out, rows.Err()
}

// ---------- контакты ----------

func (p *PostgresStore) AddContact(ctx context.Context, userID, contactID string) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO contacts (user_id, contact_id) VALUES ($1,$2)
		 ON CONFLICT (user_id, contact_id) DO NOTHING`, userID, contactID)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23503" { // нет пользователя-контакта
		return ErrNotFound
	}
	return mapErr(err)
}

func (p *PostgresStore) ListContacts(ctx context.Context, userID string) ([]string, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT contact_id FROM contacts WHERE user_id = $1 ORDER BY contact_id ASC`, userID)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	out := make([]string, 0)
	for rows.Next() {
		var cid string
		if err := rows.Scan(&cid); err != nil {
			return nil, err
		}
		out = append(out, cid)
	}
	return out, rows.Err()
}

// ---------- звонки ----------

func (p *PostgresStore) SaveCall(ctx context.Context, c *model.Call) error {
	_, err := p.pool.Exec(ctx,
		`INSERT INTO calls (id, caller_id, callee_id, video, status, created_at, ended_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7)`,
		c.ID, c.CallerID, c.CalleeID, c.Video, string(c.Status), c.CreatedAt, c.EndedAt)
	return mapErr(err)
}

func (p *PostgresStore) GetCall(ctx context.Context, id string) (*model.Call, error) {
	var c model.Call
	var status string
	err := p.pool.QueryRow(ctx,
		`SELECT id, caller_id, callee_id, video, status, created_at, ended_at FROM calls WHERE id = $1`, id).
		Scan(&c.ID, &c.CallerID, &c.CalleeID, &c.Video, &status, &c.CreatedAt, &c.EndedAt)
	if err != nil {
		return nil, mapErr(err)
	}
	c.Status = model.CallStatus(status)
	return &c, nil
}

func (p *PostgresStore) UpdateCallStatus(ctx context.Context, id string, status model.CallStatus) error {
	_, err := p.pool.Exec(ctx,
		`UPDATE calls SET status = $2,
		     ended_at = CASE WHEN $2 IN ('ended','declined','missed') THEN now() ELSE ended_at END
		 WHERE id = $1`, id, string(status))
	return mapErr(err)
}

func (p *PostgresStore) ListCallsForUser(ctx context.Context, userID string) ([]*model.Call, error) {
	rows, err := p.pool.Query(ctx,
		`SELECT id, caller_id, callee_id, video, status, created_at, ended_at
		 FROM calls WHERE caller_id = $1 OR callee_id = $1 ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, mapErr(err)
	}
	defer rows.Close()

	out := make([]*model.Call, 0)
	for rows.Next() {
		var c model.Call
		var status string
		if err := rows.Scan(&c.ID, &c.CallerID, &c.CalleeID, &c.Video, &status, &c.CreatedAt, &c.EndedAt); err != nil {
			return nil, err
		}
		c.Status = model.CallStatus(status)
		out = append(out, &c)
	}
	return out, rows.Err()
}

// DeleteUser удаляет пользователя. Каскадные удаления (ON DELETE CASCADE в
// миграциях) убирают связанные строки: one_time_prekeys, auth_tokens, messages,
// media, chat_members, contacts, calls.
func (p *PostgresStore) DeleteUser(ctx context.Context, userID string) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	// Блокировка пользователя согласована с SaveMediaWithQuota: новые файлы
	// не могут появиться между постановкой в очередь и каскадным удалением.
	var lockedID string
	if err := tx.QueryRow(ctx, `SELECT id FROM users WHERE id=$1 FOR UPDATE`, userID).Scan(&lockedID); err != nil { return mapErr(err) }
	if _, err := tx.Exec(ctx, `INSERT INTO blob_deletions(id) SELECT id FROM media WHERE owner_id=$1 ON CONFLICT DO NOTHING`, userID); err != nil { return err }

	// Чаты, где пользователь — создатель, удаляем целиком (вместе с участниками).
	if _, err := tx.Exec(ctx, `DELETE FROM chats WHERE created_by = $1`, userID); err != nil {
		return mapErr(err)
	}
	// Сообщения, отправленные пользователем, удаляем (адресованные — через cascade
	// при удалении users? нет: recipient_id ссылается на users). Явно чистим личные.
	if _, err := tx.Exec(ctx, `DELETE FROM messages WHERE sender_id = $1`, userID); err != nil {
		return mapErr(err)
	}
	// Удаляем сам аккаунт — каскад уберёт остальное.
	tag, err := tx.Exec(ctx, `DELETE FROM users WHERE id = $1`, userID)
	if err != nil {
		return mapErr(err)
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return tx.Commit(ctx)
}

func (p *PostgresStore) Close() error { p.pool.Close(); return nil }

// mapErr приводит ошибки драйвера к каноничным ErrNotFound / ErrConflict.
func mapErr(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrNotFound
	}
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" { // unique_violation
		return ErrConflict
	}
	return err
}
