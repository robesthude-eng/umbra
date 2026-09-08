package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"umbra/server/internal/model"
)

func testUser(username string) *model.User {
	return &model.User{
		ID:              "id-" + username,
		Username:        username,
		IdentityEd25519: []byte("ed25519-" + username),
		IdentityX25519:  []byte("x25519-" + username),
		SignedPrekey:    []byte("spk-" + username),
		SignedPrekeySig: []byte("sig-" + username),
		OneTimePrekeys:  [][]byte{[]byte("otk1-" + username), []byte("otk2-" + username)},
		CreatedAt:       time.Now().UTC(),
	}
}

func TestMemoryStore_UserCRUD(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()

	u := testUser("alice")
	if err := s.CreateUser(ctx, u); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	got, err := s.GetUserByID(ctx, "id-alice")
	if err != nil {
		t.Fatalf("GetUserByID: %v", err)
	}
	if got.Username != "alice" {
		t.Fatalf("ожидался username alice, получен %q", got.Username)
	}

	got2, err := s.GetUserByUsername(ctx, "alice")
	if err != nil {
		t.Fatalf("GetUserByUsername: %v", err)
	}
	if got2.ID != "id-alice" {
		t.Fatalf("ожидался id id-alice, получен %q", got2.ID)
	}

	// несуществующий
	if _, err := s.GetUserByUsername(ctx, "bob"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ожидался ErrNotFound, получен %v", err)
	}
	if _, err := s.GetUserByID(ctx, "none"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ожидался ErrNotFound, получен %v", err)
	}
}

func TestMemoryStore_UsernameConflict(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()

	if err := s.CreateUser(ctx, testUser("alice")); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	// тот же username — конфликт, даже с другим id
	dup := testUser("alice")
	dup.ID = "id-alice-2"
	if err := s.CreateUser(ctx, dup); !errors.Is(err, ErrConflict) {
		t.Fatalf("ожидался ErrConflict, получен %v", err)
	}
}

func TestMemoryStore_OneTimePrekeys(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()

	if err := s.CreateUser(ctx, testUser("alice")); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	// два pre-key, забираем по одному
	pk1, err := s.TakeOneTimePrekey(ctx, "id-alice")
	if err != nil {
		t.Fatalf("TakeOneTimePrekey 1: %v", err)
	}
	pk2, err := s.TakeOneTimePrekey(ctx, "id-alice")
	if err != nil {
		t.Fatalf("TakeOneTimePrekey 2: %v", err)
	}
	if string(pk1) == string(pk2) {
		t.Fatal("pre-key не одноразовый — вернулись одинаковые значения")
	}

	// очередь исчерпана
	if _, err := s.TakeOneTimePrekey(ctx, "id-alice"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ожидался ErrNotFound, получен %v", err)
	}
}

func TestMemoryStore_Tokens(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()

	expires := time.Now().Add(time.Hour)
	if err := s.PutToken(ctx, "hash1", "id-alice", expires); err != nil {
		t.Fatalf("PutToken: %v", err)
	}

	id, err := s.GetUserIDByTokenHash(ctx, "hash1")
	if err != nil {
		t.Fatalf("GetUserIDByTokenHash: %v", err)
	}
	if id != "id-alice" {
		t.Fatalf("ожидался id-alice, получен %q", id)
	}

	// несуществующий токен
	if _, err := s.GetUserIDByTokenHash(ctx, "nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ожидался ErrNotFound, получен %v", err)
	}

	// удаление
	if err := s.DeleteToken(ctx, "hash1"); err != nil {
		t.Fatalf("DeleteToken: %v", err)
	}
	if _, err := s.GetUserIDByTokenHash(ctx, "hash1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("после удаления ожидался ErrNotFound, получен %v", err)
	}
}

func TestMemoryStore_TokenExpiry(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()

	// токен уже истёк
	if err := s.PutToken(ctx, "expired", "id-alice", time.Now().Add(-time.Minute)); err != nil {
		t.Fatalf("PutToken: %v", err)
	}
	if _, err := s.GetUserIDByTokenHash(ctx, "expired"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("истёкший токен должен давать ErrNotFound, получен %v", err)
	}
}

func TestMemoryStore_Messages(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()

	now := time.Now().UTC()
	msgs := []*model.Message{
		{ID: "m1", SenderID: "id-alice", RecipientID: "id-bob", Ciphertext: []byte("ct1"), CreatedAt: now},
		{ID: "m2", SenderID: "id-bob", RecipientID: "id-alice", Ciphertext: []byte("ct2"), CreatedAt: now.Add(time.Second)},
		{ID: "m3", SenderID: "id-alice", RecipientID: "id-bob", Ciphertext: []byte("ct3"), CreatedAt: now.Add(2 * time.Second)},
	}
	for _, m := range msgs {
		if err := s.SaveMessage(ctx, m); err != nil {
			t.Fatalf("SaveMessage: %v", err)
		}
	}

	// Облачная история (T1): bob видит все три — m1/m3 (адресованы ему) и своё m2.
	got, err := s.ListMessages(ctx, "id-bob", time.Unix(0, 0))
	if err != nil {
		t.Fatalf("ListMessages: %v", err)
	}
	if len(got) != 3 {
		t.Fatalf("ожидалось 3 сообщения, получено %d", len(got))
	}
	if got[0].ID != "m1" || got[1].ID != "m2" || got[2].ID != "m3" {
		t.Fatalf("неверный порядок/состав: %v, %v, %v", got[0].ID, got[1].ID, got[2].ID)
	}

	// фильтр по since: только m3 (после now+1s)
	got2, err := s.ListMessages(ctx, "id-bob", now.Add(time.Second))
	if err != nil {
		t.Fatalf("ListMessages since: %v", err)
	}
	if len(got2) != 1 || got2[0].ID != "m3" {
		t.Fatalf("ожидалось только m3, получено %d", len(got2))
	}
}
