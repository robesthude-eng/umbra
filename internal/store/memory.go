package store

import (
	"context"
	"sort"
	"sync"
	"time"

	"umbra/server/internal/model"
)

// MemoryStore — in-memory реализация Store для разработки и тестов.
// Не предназначен для продакшна (данные теряются при рестарте).
type MemoryStore struct {
	mu          sync.Mutex
	users       map[string]*model.User           // id -> user
	byName      map[string]string                // username -> id
	prekeys     map[string][][]byte              // userID -> очередь одноразовых pre-keys
	tokens      map[string]tokenEntry            // tokenHash -> запись
	messages    []*model.Message                 // все сообщения (личные + групповые)
	media       map[string]*model.Media          // id -> метаданные
	chats       map[string]*model.Chat           // chatID -> чат
	chatMembers map[string]map[string]model.MemberRole // chatID -> (userID -> роль)
	contacts    map[string]map[string]bool       // userID -> (contactID -> true)
	calls       map[string]*model.Call           // callID -> звонок
}

type tokenEntry struct {
	userID  string
	expires time.Time
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{
		users:       make(map[string]*model.User),
		byName:      make(map[string]string),
		prekeys:     make(map[string][][]byte),
		tokens:      make(map[string]tokenEntry),
		media:       make(map[string]*model.Media),
		chats:       make(map[string]*model.Chat),
		chatMembers: make(map[string]map[string]model.MemberRole),
		contacts:    make(map[string]map[string]bool),
		calls:       make(map[string]*model.Call),
	}
}

func (m *MemoryStore) CreateUser(_ context.Context, u *model.User) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.byName[u.Username]; ok {
		return ErrConflict
	}
	cp := *u
	cp.OneTimePrekeys = append([][]byte(nil), u.OneTimePrekeys...)
	m.users[u.ID] = &cp
	m.byName[u.Username] = u.ID
	m.prekeys[u.ID] = append([][]byte(nil), u.OneTimePrekeys...)
	return nil
}

func (m *MemoryStore) GetUserByUsername(_ context.Context, username string) (*model.User, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.byName[username]
	if !ok {
		return nil, ErrNotFound
	}
	return m.copyUser(m.users[id]), nil
}

func (m *MemoryStore) GetUserByID(_ context.Context, id string) (*model.User, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	u, ok := m.users[id]
	if !ok {
		return nil, ErrNotFound
	}
	return m.copyUser(u), nil
}

func (m *MemoryStore) TakeOneTimePrekey(_ context.Context, userID string) ([]byte, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	queue := m.prekeys[userID]
	if len(queue) == 0 {
		return nil, ErrNotFound
	}
	pk := queue[0]
	m.prekeys[userID] = queue[1:]
	return pk, nil
}

func (m *MemoryStore) PutToken(_ context.Context, tokenHash, userID string, expires time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.tokens[tokenHash] = tokenEntry{userID: userID, expires: expires}
	return nil
}

func (m *MemoryStore) GetUserIDByTokenHash(_ context.Context, tokenHash string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	e, ok := m.tokens[tokenHash]
	if !ok {
		return "", ErrNotFound
	}
	if time.Now().After(e.expires) {
		delete(m.tokens, tokenHash)
		return "", ErrNotFound
	}
	return e.userID, nil
}

func (m *MemoryStore) DeleteToken(_ context.Context, tokenHash string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.tokens, tokenHash)
	return nil
}

func (m *MemoryStore) SaveMessage(_ context.Context, msg *model.Message) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	cp := *msg
	m.messages = append(m.messages, &cp)
	return nil
}

func (m *MemoryStore) ListMessages(_ context.Context, userID string, since time.Time) ([]*model.Message, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := time.Now()
	out := make([]*model.Message, 0)
	for _, msg := range m.messages {
		if !msg.CreatedAt.After(since) {
			continue
		}
		// Самоуничтожение: просроченные сообщения не отдаются.
		if msg.ExpiresAt != nil && !msg.ExpiresAt.After(now) {
			continue
		}
		if msg.RecipientID == userID {
			cp := *msg
			out = append(out, &cp)
			continue
		}
		if msg.ChatID != "" && m.isMember(msg.ChatID, userID) {
			cp := *msg
			out = append(out, &cp)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out, nil
}

func (m *MemoryStore) SaveMedia(_ context.Context, media *model.Media) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.media[media.ID]; ok {
		return ErrConflict
	}
	if _, ok := m.users[media.OwnerID]; !ok {
		return ErrNotFound
	}
	cp := *media
	m.media[media.ID] = &cp
	return nil
}

func (m *MemoryStore) GetMedia(_ context.Context, id string) (*model.Media, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	media, ok := m.media[id]
	if !ok {
		return nil, ErrNotFound
	}
	cp := *media
	return &cp, nil
}

func (m *MemoryStore) MediaBytesForUser(_ context.Context, userID string) (int64, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var total int64
	for _, media := range m.media {
		if media.OwnerID == userID {
			total += media.Size
		}
	}
	return total, nil
}

// ---------- чаты ----------

func (m *MemoryStore) CreateChat(_ context.Context, c *model.Chat) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.chats[c.ID]; ok {
		return ErrConflict
	}
	cp := *c
	m.chats[c.ID] = &cp
	m.chatMembers[c.ID] = map[string]model.MemberRole{c.CreatedBy: model.RoleOwner}
	return nil
}

func (m *MemoryStore) GetChat(_ context.Context, chatID string) (*model.Chat, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.chats[chatID]
	if !ok {
		return nil, ErrNotFound
	}
	cp := *c
	return &cp, nil
}

func (m *MemoryStore) AddMember(_ context.Context, chatID, userID string, role model.MemberRole) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	members, ok := m.chatMembers[chatID]
	if !ok {
		return ErrNotFound
	}
	if _, exists := members[userID]; exists {
		return ErrConflict
	}
	members[userID] = role
	return nil
}

func (m *MemoryStore) RemoveMember(_ context.Context, chatID, userID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	members, ok := m.chatMembers[chatID]
	if !ok {
		return ErrNotFound
	}
	if members[userID] == model.RoleOwner {
		return ErrForbidden
	}
	if _, exists := members[userID]; !exists {
		return ErrNotFound
	}
	delete(members, userID)
	return nil
}

func (m *MemoryStore) GetMember(_ context.Context, chatID, userID string) (*model.ChatMember, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	members, ok := m.chatMembers[chatID]
	if !ok {
		return nil, ErrNotFound
	}
	role, exists := members[userID]
	if !exists {
		return nil, ErrNotFound
	}
	return &model.ChatMember{ChatID: chatID, UserID: userID, Role: role}, nil
}

func (m *MemoryStore) ListMembers(_ context.Context, chatID string) ([]*model.ChatMember, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	members, ok := m.chatMembers[chatID]
	if !ok {
		return nil, ErrNotFound
	}
	out := make([]*model.ChatMember, 0, len(members))
	for uid, role := range members {
		out = append(out, &model.ChatMember{ChatID: chatID, UserID: uid, Role: role})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].UserID < out[j].UserID })
	return out, nil
}

func (m *MemoryStore) ListChatsForUser(_ context.Context, userID string) ([]*model.Chat, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]*model.Chat, 0)
	for chatID, members := range m.chatMembers {
		if _, ok := members[userID]; !ok {
			continue
		}
		if c, ok := m.chats[chatID]; ok {
			cp := *c
			out = append(out, &cp)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out, nil
}

// ---------- контакты ----------

func (m *MemoryStore) AddContact(_ context.Context, userID, contactID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.users[contactID]; !ok {
		return ErrNotFound
	}
	set, ok := m.contacts[userID]
	if !ok {
		set = make(map[string]bool)
		m.contacts[userID] = set
	}
	set[contactID] = true
	return nil
}

func (m *MemoryStore) ListContacts(_ context.Context, userID string) ([]string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	set := m.contacts[userID]
	out := make([]string, 0, len(set))
	for cid := range set {
		out = append(out, cid)
	}
	sort.Strings(out)
	return out, nil
}

func (m *MemoryStore) Close() error { return nil }

// DeleteUser полностью удаляет пользователя и все связанные данные.
func (m *MemoryStore) DeleteUser(_ context.Context, userID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	u, ok := m.users[userID]
	if !ok {
		return ErrNotFound
	}
	username := u.Username

	delete(m.users, userID)
	delete(m.byName, username)
	delete(m.prekeys, userID)

	// токены
	for h, e := range m.tokens {
		if e.userID == userID {
			delete(m.tokens, h)
		}
	}
	// сообщения (личные и групповые, где он участник)
	kept := m.messages[:0]
	for _, msg := range m.messages {
		if msg.SenderID == userID || msg.RecipientID == userID {
			continue
		}
		if msg.ChatID != "" && m.isMember(msg.ChatID, userID) {
			continue
		}
		kept = append(kept, msg)
	}
	m.messages = kept
	// медиа
	for mid, media := range m.media {
		if media.OwnerID == userID {
			delete(m.media, mid)
		}
	}
	// чаты: удаляем членство; чат без owner удаляем
	for chatID, members := range m.chatMembers {
		if _, ok := members[userID]; ok {
			delete(members, userID)
		}
		chat := m.chats[chatID]
		if chat != nil && chat.CreatedBy == userID {
			delete(m.chats, chatID)
			delete(m.chatMembers, chatID)
		}
	}
	// контакты (в обе стороны)
	for uid, set := range m.contacts {
		if uid == userID {
			delete(m.contacts, uid)
			continue
		}
		delete(set, userID)
	}
	// звонки
	for cid, call := range m.calls {
		if call.CallerID == userID || call.CalleeID == userID {
			delete(m.calls, cid)
		}
	}
	return nil
}

// ---------- звонки ----------

func (m *MemoryStore) SaveCall(_ context.Context, c *model.Call) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.calls[c.ID]; ok {
		return ErrConflict
	}
	cp := *c
	if c.EndedAt != nil {
		t := *c.EndedAt
		cp.EndedAt = &t
	}
	m.calls[c.ID] = &cp
	return nil
}

func (m *MemoryStore) GetCall(_ context.Context, id string) (*model.Call, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.calls[id]
	if !ok {
		return nil, ErrNotFound
	}
	cp := *c
	if c.EndedAt != nil {
		t := *c.EndedAt
		cp.EndedAt = &t
	}
	return &cp, nil
}

func (m *MemoryStore) UpdateCallStatus(_ context.Context, id string, status model.CallStatus) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	c, ok := m.calls[id]
	if !ok {
		return ErrNotFound
	}
	c.Status = status
	if status == model.CallEnded || status == model.CallDeclined || status == model.CallMissed {
		now := time.Now().UTC()
		c.EndedAt = &now
	}
	return nil
}

func (m *MemoryStore) ListCallsForUser(_ context.Context, userID string) ([]*model.Call, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]*model.Call, 0)
	for _, c := range m.calls {
		if c.CallerID == userID || c.CalleeID == userID {
			cp := *c
			if c.EndedAt != nil {
				t := *c.EndedAt
				cp.EndedAt = &t
			}
			out = append(out, &cp)
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return out, nil
}

// isMember — вызывается только под m.mu.
func (m *MemoryStore) isMember(chatID, userID string) bool {
	members, ok := m.chatMembers[chatID]
	if !ok {
		return false
	}
	_, ok = members[userID]
	return ok
}

func (m *MemoryStore) copyUser(u *model.User) *model.User {
	if u == nil {
		return nil
	}
	cp := *u
	cp.IdentityEd25519 = append([]byte(nil), u.IdentityEd25519...)
	cp.IdentityX25519 = append([]byte(nil), u.IdentityX25519...)
	cp.SignedPrekey = append([]byte(nil), u.SignedPrekey...)
	cp.SignedPrekeySig = append([]byte(nil), u.SignedPrekeySig...)
	return &cp
}
