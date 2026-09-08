// Package model описывает доменные сущности мессенджера.
// ВАЖНО: сервер хранит только ПУБЛИЧНЫЕ ключи и ШИФРОТЕКСТ.
// Никаких приватных ключей и открытого текста сообщений на сервере нет.
package model

import "time"

// User — учётная запись. IdentityEd25519 используется для подписи (аутентификация),
// IdentityX25519 и pre-keys — для end-to-end шифрования на стороне клиентов (X3DH).
type User struct {
	ID              string    `json:"id"`
	Username        string    `json:"username"`
	// Phone — нормализованный номер E.164 (например "+79991234567"); у legacy-
	// аккаунтов, зарегистрированных только по username, может быть пустым.
	Phone string `json:"phone"`
	// PhoneHash — SHA-256(hex) от нормализованного номера. Используется для
	// приватного поиска контактов: клиент присылает хэши телефонной книги.
	PhoneHash string `json:"phone_hash"`
	// DisplayName — имя, указанное при регистрации (как человек представляется).
	DisplayName     string    `json:"display_name"`
	IdentityEd25519 []byte    `json:"identity_ed25519"`        // открытый ключ подписи (32 байта)
	IdentityX25519  []byte    `json:"identity_x25519"`         // raw 32 байта (legacy) или Signal 33 байта
	SignedPrekey    []byte    `json:"signed_prekey"`           // подписанный pre-key X25519
	SignedPrekeySig []byte    `json:"signed_prekey_signature"` // v1: Ed25519, v2: подпись identity-ключом Signal
	OneTimePrekeys  [][]byte  `json:"one_time_prekeys"`        // одноразовые pre-keys
	KeyVersion      int       `json:"key_version"` // 1: legacy, 2: Signal + явные id
	RegistrationID  int       `json:"registration_id"`
	SignedPrekeyID  int       `json:"signed_prekey_id"`
	KeyBundleID     string    `json:"key_bundle_id"`
	CreatedAt       time.Time `json:"created_at"`
}

// Message — сообщение. Ciphertext — уже зашифрованный на клиенте блоб.
// Сервер не может его прочитать и не знает тип/размер содержимого.
//
// Адресация: для личного сообщения заполнен RecipientID; для группового/канального —
// заполнен ChatID (RecipientID при этом пуст). Сервер не различает содержимое,
// только маршрутизирует шифротекст.
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

// Media — метаданные ciphertext; имя файла, ключ и nonce серверу не передаются.
type Media struct {
	ID          string    `json:"id"`
	OwnerID     string    `json:"owner_id"`
	ContentType string    `json:"content_type"`
	Size        int64     `json:"size"`
	CreatedAt   time.Time `json:"created_at"`
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

// Chat — групповой чат или канал. Содержимое сообщений сервер не видит.
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

// Contact — контакт пользователя (односторонняя ссылка на другого пользователя).
type Contact struct {
	UserID    string    `json:"user_id"`
	ContactID string    `json:"contact_id"`
	CreatedAt time.Time `json:"created_at"`
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

// Call — запись о звонке (голосовом или видео). Сервер хранит только метаданные
// звонка: медиа-поток идёт peer-to-peer (WebRTC) и через сервер не проходит.
type Call struct {
	ID        string     `json:"id"`
	CallerID  string     `json:"caller_id"`
	CalleeID  string     `json:"callee_id"`
	Video     bool       `json:"video"`
	Status    CallStatus `json:"status"`
	CreatedAt time.Time  `json:"created_at"`
	EndedAt   *time.Time `json:"ended_at"`
}
