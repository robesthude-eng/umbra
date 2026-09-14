// Package model описывает доменные сущности мессенджера.
// ВАЖНО: сервер хранит только ПУБЛИЧНЫЕ ключи и ШИФРОТЕКСТ.
// Никаких приватных ключей и открытого текста сообщений на сервере нет.
package model

import "time"

// User — учётная запись. IdentityEd25519 используется для подписи (аутентификация),
// IdentityX25519 и pre-keys — для end-to-end шифрования на стороне клиентов (X3DH).
type User struct {
	ID       string `json:"id"`
	Username string `json:"username"`
	// Phone — нормализованный номер E.164 (например "+79991234567"); у legacy-
	// аккаунтов, зарегистрированных только по username, может быть пустым.
	Phone string `json:"phone"`
	// PhoneHash — SHA-256(hex) от нормализованного номера. Используется для
	// приватного поиска контактов: клиент присылает хэши телефонной книги.
	PhoneHash string `json:"phone_hash"`
	// DisplayName — имя, указанное при регистрации (как человек представляется).
	DisplayName string `json:"display_name"`
	// LastName — фамилия (необязательно при регистрации).
	LastName        string    `json:"last_name,omitempty"`
	IdentityEd25519 []byte    `json:"identity_ed25519"`        // открытый ключ подписи (32 байта)
	IdentityX25519  []byte    `json:"identity_x25519"`         // raw 32 байта (legacy) или Signal 33 байта
	SignedPrekey    []byte    `json:"signed_prekey"`           // подписанный pre-key X25519
	SignedPrekeySig []byte    `json:"signed_prekey_signature"` // v1: Ed25519, v2: подпись identity-ключом Signal
	OneTimePrekeys  [][]byte  `json:"one_time_prekeys"`        // одноразовые pre-keys
	KeyVersion      int       `json:"key_version"`             // 1: legacy, 2: Signal + явные id
	RegistrationID  int       `json:"registration_id"`
	SignedPrekeyID  int       `json:"signed_prekey_id"`
	KeyBundleID     string    `json:"key_bundle_id"`
	CreatedAt       time.Time `json:"created_at"`
}

// Message — API-конверт сообщения. Шифрование при хранении выполняет сервер.
//
// Адресация: для личного сообщения заполнен RecipientID; для группового/канального —
// заполнен ChatID (RecipientID при этом пуст). Ciphertext — историческое имя
// API-поля; облачный сервер шифрует его при сохранении и расшифровывает при чтении.
//
// ExpiresAt — для секретных чатов: если задано, сообщение самоуничтожается после
// этого момента. Сервер не отдаёт просроченные сообщения и удаляет их.
type Message struct {
	ID          string     `json:"id"`
	SenderID    string     `json:"sender_id"`
	RecipientID string     `json:"recipient_id"` // личное сообщение
	ChatID      string     `json:"chat_id"`      // групповое/канальное сообщение
	Ciphertext  []byte     `json:"ciphertext"`
	CreatedAt   time.Time  `json:"created_at"`
	ExpiresAt   *time.Time `json:"expires_at"` // nil = без самоуничтожения
	ClientID    string     `json:"client_message_id,omitempty"`
	ExpiresIn   int64      `json:"-"` // исходный TTL для проверки повторов
}

// Media — метаданные облачного вложения. Size — размер исходных байтов.
//
// ChatID/RecipientID — область видимости файла. Она задаётся при загрузке и
// определяет, кто может скачать blob: раньше знание id давало доступ любому
// авторизованному пользователю, и пересланный id открывал файл посторонним.
// Пустая область = медиа, загруженное старым клиентом (см. MediaOpenAccess).
type Media struct {
	StorageFormat int       `json:"-"`
	BlobID        string    `json:"-"`
	ID            string    `json:"id"`
	OwnerID       string    `json:"owner_id"`
	ContentType   string    `json:"content_type"`
	Size          int64     `json:"size"`
	CreatedAt     time.Time `json:"created_at"`
	// ChatID — файл доступен участникам этой группы/канала.
	ChatID string `json:"chat_id,omitempty"`
	// RecipientID — файл доступен владельцу и этому собеседнику (личный чат).
	RecipientID string `json:"recipient_id,omitempty"`
}

// ObjectID resolves the physical blob while preserving public media URLs.
func (m *Media) ObjectID() string {
	if m.BlobID != "" {
		return m.BlobID
	}
	return m.ID
}

// ChatType — тип чата: группа или канал.
type ChatType string

const (
	ChatGroup   ChatType = "group"
	ChatChannel ChatType = "channel"
)

// MemberRole — роль участника чата.
type MemberRole string

const (
	RoleOwner  MemberRole = "owner"
	RoleAdmin  MemberRole = "admin"
	RoleMember MemberRole = "member"
)

// Chat — групповой чат или канал облачного сервера.
type Chat struct {
	ID        string    `json:"id"`
	Type      ChatType  `json:"type"`
	Title     string    `json:"title"`
	CreatedBy string    `json:"created_by"`
	CreatedAt time.Time `json:"created_at"`
}

// ChatMember — участник чата.
type ChatMember struct {
	ChatID   string     `json:"chat_id"`
	UserID   string     `json:"user_id"`
	Role     MemberRole `json:"role"`
	JoinedAt time.Time  `json:"joined_at"`
}

// CallStatus — статус звонка.
type CallStatus string

const (
	CallRinging  CallStatus = "ringing"
	CallActive   CallStatus = "active"
	CallEnded    CallStatus = "ended"
	CallMissed   CallStatus = "missed"
	CallDeclined CallStatus = "declined"
)

// Call — запись о звонке (голосовой или видео). Сервер хранит только метаданные
// звонка: медиа-поток идёт peer-to-peer (WebRTC) и через сервер не проходит.
//
// Participants — полный список участников, включая звонящего (появился в 0.9.0
// вместе с групповыми звонками). У записей 0.8.0 он пуст: там звонок всегда был
// на двоих, поэтому список собирается из CallerID и CalleeID. CalleeID заполнен
// и в группе (первый приглашённый) — так старые клиенты и база остаются целы.
type Call struct {
	ID           string     `json:"id"`
	CallerID     string     `json:"caller_id"`
	CalleeID     string     `json:"callee_id"`
	Participants []string   `json:"participants"`
	Video        bool       `json:"video"`
	Status       CallStatus `json:"status"`
	CreatedAt    time.Time  `json:"created_at"`
	EndedAt      *time.Time `json:"ended_at"`
}

// MaxCallParticipants — предел mesh-схемы: каждый участник держит соединение
// с каждым, поэтому вчетвером это уже три потока на телефон. Больше — нужен SFU.
const MaxCallParticipants = 4

// Everyone — все участники звонка, включая звонящего.
func (c *Call) Everyone() []string {
	if len(c.Participants) > 0 {
		out := make([]string, 0, len(c.Participants))
		for _, id := range c.Participants {
			if id != "" {
				out = append(out, id)
			}
		}
		return out
	}
	if c.CalleeID == "" {
		return []string{c.CallerID}
	}
	return []string{c.CallerID, c.CalleeID}
}

// IsParticipant — участвует ли человек в звонке.
func (c *Call) IsParticipant(userID string) bool {
	if userID == "" {
		return false
	}
	for _, id := range c.Everyone() {
		if id == userID {
			return true
		}
	}
	return false
}

// Others — остальные участники звонка. nil означает «этот человек не участник»,
// а пустой не-nil список — «участник, но кроме него никого не осталось».
func (c *Call) Others(userID string) []string {
	member := false
	out := make([]string, 0, MaxCallParticipants)
	for _, id := range c.Everyone() {
		if id == userID {
			member = true
			continue
		}
		out = append(out, id)
	}
	if !member {
		return nil
	}
	return out
}

// PushDevice — токен устройства для push-уведомлений. У одного человека
// может быть несколько телефонов, поэтому храним списком.
type PushDevice struct {
	SessionID string    `json:"-"`
	Token     string    `json:"token"`
	UserID    string    `json:"user_id"`
	Platform  string    `json:"platform"`
	UpdatedAt time.Time `json:"updated_at"`
}

// AuthSession exposes a public session identifier, never bearer credentials.
type AuthSession struct {
	ID         string    `json:"id"`
	UserID     string    `json:"-"`
	TokenHash  string    `json:"-"`
	DeviceName string    `json:"device_name"`
	CreatedAt  time.Time `json:"created_at"`
	LastSeenAt time.Time `json:"last_seen_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	Current    bool      `json:"current"`
}
