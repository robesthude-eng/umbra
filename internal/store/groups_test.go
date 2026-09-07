package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"umbra/server/internal/model"
)

func newTestChat(id string, typ model.ChatType, createdBy string) *model.Chat {
	return &model.Chat{
		ID:        id,
		Type:      typ,
		Title:     "чат " + id,
		CreatedBy: createdBy,
		CreatedAt:  time.Now().UTC(),
	}
}

func TestMemoryStore_CreateChatOwner(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()
	if err := s.CreateUser(ctx, testUser("alice")); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	if err := s.CreateChat(ctx, newTestChat("g1", model.ChatGroup, "id-alice")); err != nil {
		t.Fatalf("CreateChat: %v", err)
	}

	// создатель автоматически owner
	member, err := s.GetMember(ctx, "g1", "id-alice")
	if err != nil {
		t.Fatalf("GetMember: %v", err)
	}
	if member.Role != model.RoleOwner {
		t.Fatalf("ожидалась роль owner, получена %q", member.Role)
	}

	// конфликт id чата
	if err := s.CreateChat(ctx, newTestChat("g1", model.ChatGroup, "id-alice")); !errors.Is(err, ErrConflict) {
		t.Fatalf("ожидался ErrConflict, получен %v", err)
	}
}

func TestMemoryStore_Members(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()
	_ = s.CreateUser(ctx, testUser("alice"))
	_ = s.CreateUser(ctx, testUser("bob"))
	_ = s.CreateUser(ctx, testUser("carol"))
	_ = s.CreateChat(ctx, newTestChat("g1", model.ChatGroup, "id-alice"))

	// alice (owner) добавляет bob
	if err := s.AddMember(ctx, "g1", "id-bob", model.RoleMember); err != nil {
		t.Fatalf("AddMember bob: %v", err)
	}
	// повторное добавление — конфликт
	if err := s.AddMember(ctx, "g1", "id-bob", model.RoleMember); !errors.Is(err, ErrConflict) {
		t.Fatalf("ожидался ErrConflict, получен %v", err)
	}

	// список участников — двое
	members, err := s.ListMembers(ctx, "g1")
	if err != nil {
		t.Fatalf("ListMembers: %v", err)
	}
	if len(members) != 2 {
		t.Fatalf("ожидалось 2 участника, получено %d", len(members))
	}

	// удаление owner запрещено
	if err := s.RemoveMember(ctx, "g1", "id-alice"); !errors.Is(err, ErrForbidden) {
		t.Fatalf("ожидался ErrForbidden, получен %v", err)
	}

	// удаление участника
	if err := s.RemoveMember(ctx, "g1", "id-bob"); err != nil {
		t.Fatalf("RemoveMember bob: %v", err)
	}
	if _, err := s.GetMember(ctx, "g1", "id-bob"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ожидался ErrNotFound, получен %v", err)
	}

	// чаты пользователя
	chats, err := s.ListChatsForUser(ctx, "id-alice")
	if err != nil || len(chats) != 1 {
		t.Fatalf("ListChatsForUser: %v, len=%d", err, len(chats))
	}
	// bob больше не участник
	chatsBob, _ := s.ListChatsForUser(ctx, "id-bob")
	if len(chatsBob) != 0 {
		t.Fatalf("bob не должен видеть чаты после удаления, получено %d", len(chatsBob))
	}
}

func TestMemoryStore_GroupMessages(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()
	_ = s.CreateUser(ctx, testUser("alice"))
	_ = s.CreateUser(ctx, testUser("bob"))
	_ = s.CreateUser(ctx, testUser("carol"))
	_ = s.CreateChat(ctx, newTestChat("g1", model.ChatGroup, "id-alice"))
	_ = s.AddMember(ctx, "g1", "id-bob", model.RoleMember)

	now := time.Now().UTC()
	// групповое сообщение
	groupMsg := &model.Message{ID: "gm1", SenderID: "id-alice", ChatID: "g1", Ciphertext: []byte("ct"), CreatedAt: now}
	if err := s.SaveMessage(ctx, groupMsg); err != nil {
		t.Fatalf("SaveMessage: %v", err)
	}
	// личное сообщение alice -> carol
	dm := &model.Message{ID: "dm1", SenderID: "id-alice", RecipientID: "id-carol", Ciphertext: []byte("ct"), CreatedAt: now.Add(time.Second)}
	_ = s.SaveMessage(ctx, dm)

	// bob — участник группы — видит групповое
	gotBob, _ := s.ListMessages(ctx, "id-bob", time.Unix(0, 0))
	if len(gotBob) != 1 || gotBob[0].ID != "gm1" {
		t.Fatalf("bob должен видеть только групповое сообщение, получено %d", len(gotBob))
	}
	// carol — не участник группы — видит только личное dm1
	gotCarol, _ := s.ListMessages(ctx, "id-carol", time.Unix(0, 0))
	if len(gotCarol) != 1 || gotCarol[0].ID != "dm1" {
		t.Fatalf("carol должен видеть только личное сообщение, получено %d", len(gotCarol))
	}
	// alice видит только групповое (dm1 — исходящее личное, в её inbox не попадает)
	gotAlice, _ := s.ListMessages(ctx, "id-alice", time.Unix(0, 0))
	if len(gotAlice) != 1 || gotAlice[0].ID != "gm1" {
		t.Fatalf("alice должен видеть только групповое сообщение, получено %d", len(gotAlice))
	}
}

func TestMemoryStore_Contacts(t *testing.T) {
	ctx := context.Background()
	s := NewMemoryStore()
	_ = s.CreateUser(ctx, testUser("alice"))
	_ = s.CreateUser(ctx, testUser("bob"))

	// несуществующий контакт
	if err := s.AddContact(ctx, "id-alice", "no-such"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("ожидался ErrNotFound, получен %v", err)
	}

	if err := s.AddContact(ctx, "id-alice", "id-bob"); err != nil {
		t.Fatalf("AddContact: %v", err)
	}
	// повторное добавление — идемпотентно (не ошибка)
	if err := s.AddContact(ctx, "id-alice", "id-bob"); err != nil {
		t.Fatalf("повторное AddContact: %v", err)
	}

	contacts, err := s.ListContacts(ctx, "id-alice")
	if err != nil || len(contacts) != 1 || contacts[0] != "id-bob" {
		t.Fatalf("ListContacts: %v, %v", err, contacts)
	}
	// у bob контактов нет
	contactsBob, _ := s.ListContacts(ctx, "id-bob")
	if len(contactsBob) != 0 {
		t.Fatalf("у bob не должно быть контактов, получено %d", len(contactsBob))
	}
}
