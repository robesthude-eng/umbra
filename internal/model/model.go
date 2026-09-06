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
	IdentityEd25519 []byte    `json:"identity_ed25519"`        // открытый ключ подписи (32 байта)
	IdentityX25519  []byte    `json:"identity_x25519"`         // открытый ключ E2E (32 байта)
	SignedPrekey    []byte    `json:"signed_prekey"`           // подписанный pre-key X25519
	SignedPrekeySig []byte    `json:"signed_prekey_signature"` // ed25519-подпись pre-key
	OneTimePrekeys  [][]byte  `json:"one_time_prekeys"`        // одноразовые pre-keys
	CreatedAt       time.Time `json:"created_at"`
}

// Message — сообщение. Ciphertext — уже зашифрованный на клиенте блоб.
// Сервер не может его прочитать и не знает тип/размер содержимого.
type Message struct {
	ID          string    `json:"id"`
	SenderID    string    `json:"sender_id"`
	RecipientID string    `json:"recipient_id"`
	Ciphertext  []byte    `json:"ciphertext"`
	CreatedAt   time.Time `json:"created_at"`
}

// Media — метаданные ciphertext; имя файла, ключ и nonce серверу не передаются.
type Media struct {
	ID          string    `json:"id"`
	OwnerID     string    `json:"owner_id"`
	ContentType string    `json:"content_type"`
	Size        int64     `json:"size"`
	CreatedAt   time.Time `json:"created_at"`
}
