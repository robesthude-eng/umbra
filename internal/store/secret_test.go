package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"umbra/server/internal/model"
)

func TestMemoryStore_DeleteUser(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()
	_ = s.CreateUser(ctx, testUser("alice"))
	_ = s.CreateUser(ctx, testUser("bob"))

	// alice шлёт сообщение bob'у и добавляет контакт
	_ = s.SaveMessage(ctx, &model.Message{ID: "m1", SenderID: "id-alice", RecipientID: "id-bob", Ciphertext: []byte("x"), CreatedAt: time.Now()})
	_ = s.AddContact(ctx, "id-alice", "id-bob")

	// удаляем alice
	if err := s.DeleteUser(ctx, "id-alice"); err != nil {
		t.Fatalf("DeleteUser: %v", err)
	}

	// пользователь исчез
	if _, err := s.GetUserByID(ctx, "id-alice"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ожидался ErrNotFound, получен %v", err)
	}
	if _, err := s.GetUserByUsername(ctx, "alice"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("username должен быть удалён: %v", err)
	}

	// сообщения alice удалены — bob не видит их
	msgs, _ := s.ListMessages(ctx, "id-bob", time.Unix(0, 0))
	if len(msgs) != 0 {
		t.Fatalf("сообщения удалённого пользователя должны исчезнуть, len=%d", len(msgs))
	}

	// повторное удаление — ErrNotFound
	if err := s.DeleteUser(ctx, "id-alice"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("повторное удаление должно дать ErrNotFound, получен %v", err)
	}
}

func TestMemoryStore_SelfDestructFilter(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()
	_ = s.CreateUser(ctx, testUser("alice"))
	_ = s.CreateUser(ctx, testUser("bob"))

	now := time.Now().UTC()
	past := now.Add(-time.Minute)
	// истёкшее сообщение
	_ = s.SaveMessage(ctx, &model.Message{ID: "exp", SenderID: "id-alice", RecipientID: "id-bob", Ciphertext: []byte("x"), CreatedAt: now.Add(-2 * time.Minute), ExpiresAt: &past})
	// живое сообщение без таймера
	_ = s.SaveMessage(ctx, &model.Message{ID: "live", SenderID: "id-alice", RecipientID: "id-bob", Ciphertext: []byte("x"), CreatedAt: now.Add(-time.Minute)})

	msgs, _ := s.ListMessages(ctx, "id-bob", time.Unix(0, 0))
	if len(msgs) != 1 || msgs[0].ID != "live" {
		t.Fatalf("истёкшее сообщение должно быть отфильтровано, получено %d", len(msgs))
	}
}
