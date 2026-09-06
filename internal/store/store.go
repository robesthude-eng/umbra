// Package store — абстракция хранилища. Позволяет запускать сервер
// с in-memory хранилищем (разработка/тесты) или PostgreSQL (продакшн).
package store

import (
	"context"
	"errors"
	"time"

	"umbra/server/internal/model"
)

var (
	// ErrNotFound — запись не найдена.
	ErrNotFound = errors.New("store: not found")
	// ErrConflict — конфликт (например, username уже занят).
	ErrConflict = errors.New("store: conflict")
	// ErrForbidden — операция запрещена правилами (например, удаление владельца).
	ErrForbidden = errors.New("store: forbidden")
)

// Store — единый интерфейс персистентности.
type Store interface {
	// Пользователи и ключи.
	CreateUser(ctx context.Context, u *model.User) error
	GetUserByUsername(ctx context.Context, username string) (*model.User, error)
	GetUserByID(ctx context.Context, id string) (*model.User, error)
	// TakeOneTimePrekey извлекает и УДАЛЯЕТ один одноразовый pre-key (однократное использование).
	TakeOneTimePrekey(ctx context.Context, userID string) ([]byte, error)

	// Сессионные токены.
	PutToken(ctx context.Context, tokenHash, userID string, expires time.Time) error
	GetUserIDByTokenHash(ctx context.Context, tokenHash string) (string, error)
	DeleteToken(ctx context.Context, tokenHash string) error

	// Сообщения (хранится только ciphertext).
	SaveMessage(ctx context.Context, m *model.Message) error
	// ListMessages возвращает личные (recipient_id = userID) и групповые
	// (userID — участник чата) сообщения, созданные после since.
	ListMessages(ctx context.Context, userID string, since time.Time) ([]*model.Message, error)

	// Метаданные медиа; зашифрованные байты хранятся отдельно в BlobStore.
	SaveMedia(ctx context.Context, m *model.Media) error
	GetMedia(ctx context.Context, id string) (*model.Media, error)
	// MediaBytesForUser — суммарный объём медиа пользователя (для квоты).
	MediaBytesForUser(ctx context.Context, userID string) (int64, error)

	// Чаты, участники и роли.
	CreateChat(ctx context.Context, c *model.Chat) error // создаёт чат и добавляет создателя как owner
	GetChat(ctx context.Context, chatID string) (*model.Chat, error)
	AddMember(ctx context.Context, chatID, userID string, role model.MemberRole) error
	RemoveMember(ctx context.Context, chatID, userID string) error
	GetMember(ctx context.Context, chatID, userID string) (*model.ChatMember, error)
	ListMembers(ctx context.Context, chatID string) ([]*model.ChatMember, error)
	ListChatsForUser(ctx context.Context, userID string) ([]*model.Chat, error)

	// Контакты.
	AddContact(ctx context.Context, userID, contactID string) error
	ListContacts(ctx context.Context, userID string) ([]string, error)

	// Звонки (метаданные; медиа идёт peer-to-peer).
	SaveCall(ctx context.Context, c *model.Call) error
	GetCall(ctx context.Context, id string) (*model.Call, error)
	UpdateCallStatus(ctx context.Context, id string, status model.CallStatus) error
	ListCallsForUser(ctx context.Context, userID string) ([]*model.Call, error)

	// DeleteUser полностью удаляет пользователя и все его данные (аккаунт,
	// ключи, сообщения, медиа, членства в чатах, контакты, звонки) — «сжечь аккаунт».
	DeleteUser(ctx context.Context, userID string) error

	Close() error
}
