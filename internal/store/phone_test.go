package store

import (
	"context"
	"testing"

	"umbra/server/internal/model"
)

func TestMemoryStorePhoneIndex(t *testing.T) {
	m := NewMemoryStore()
	ctx := context.Background()

	u1 := &model.User{ID: "id1", Username: "user1", Phone: "+79991112233", PhoneHash: "hash1", DisplayName: "One"}
	if err := m.CreateUser(ctx, u1); err != nil {
		t.Fatalf("CreateUser u1: %v", err)
	}
	// Конфликт по телефону (другой username, тот же номер).
	u2 := &model.User{ID: "id2", Username: "user2", Phone: "+79991112233", PhoneHash: "hash1"}
	if err := m.CreateUser(ctx, u2); err != ErrConflict {
		t.Fatalf("ожидался ErrConflict по телефону, получено %v", err)
	}

	got, err := m.GetUserByPhone(ctx, "+79991112233")
	if err != nil || got.ID != "id1" {
		t.Fatalf("GetUserByPhone: %v %v", got, err)
	}
	if _, err := m.GetUserByPhone(ctx, "+70000000000"); err != ErrNotFound {
		t.Fatalf("неизвестный номер: ожидался ErrNotFound, получено %v", err)
	}

	// Legacy-пользователь без телефона не участвует в поиске контактов.
	if err := m.CreateUser(ctx, &model.User{ID: "id3", Username: "legacy"}); err != nil {
		t.Fatalf("CreateUser legacy: %v", err)
	}
	found, err := m.FindUsersByPhoneHashes(ctx, []string{"hash1", "unknown", "hash1"})
	if err != nil {
		t.Fatalf("FindUsersByPhoneHashes: %v", err)
	}
	if len(found) != 1 || found[0].ID != "id1" {
		t.Fatalf("ожидался один результат id1, получено %v", found)
	}

	// DeleteUser очищает индексы: номер освобождается для новой регистрации.
	if err := m.DeleteUser(ctx, "id1"); err != nil {
		t.Fatalf("DeleteUser: %v", err)
	}
	if _, err := m.GetUserByPhone(ctx, "+79991112233"); err != ErrNotFound {
		t.Fatalf("после удаления номер должен быть свободен, получено %v", err)
	}
	if err := m.CreateUser(ctx, &model.User{ID: "id4", Username: "user4", Phone: "+79991112233", PhoneHash: "hash1"}); err != nil {
		t.Fatalf("повторная регистрация освобождённого номера: %v", err)
	}
}
