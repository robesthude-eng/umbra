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
	ListMessages(ctx context.Context, userID string, since time.Time) ([]*model.Message, error)

	// Метаданные медиа; зашифрованные байты хранятся отдельно в BlobStore.
	SaveMedia(ctx context.Context, m *model.Media) error
	GetMedia(ctx context.Context, id string) (*model.Media, error)

	Close() error
}
