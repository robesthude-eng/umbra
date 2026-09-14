package store

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"umbra/server/internal/model"
)

// Единый набор кейсов для обеих реализаций Store. Раньше поведение
// MemoryStore проверялось десятками тестов, а PostgresStore — отдельными
// интеграционными сценариями, поэтому расхождения в семантике ловились только
// на бою. Контракт ниже запускается на обоих хранилищах одинаково.
//
// PostgreSQL-часть пропускается без TEST_DATABASE_URL, как и остальные
// интеграционные тесты пакета.

func TestMemoryStoreConformance(t *testing.T) {
	storeContract(t, NewMemoryStore())
}

func TestPostgresStoreConformance(t *testing.T) {
	st, done := newPostgresContractStore(t, "umbra_conformance")
	defer done()
	storeContract(t, st)
}

// storeContract — поведение, на которое опирается HTTP-слой: его обязаны
// одинаково выполнять и память, и PostgreSQL.
func storeContract(t *testing.T, st Store) {
	t.Helper()
	ctx := context.Background()
	now := time.Now().UTC().Truncate(time.Millisecond)

	alice := testUser("conf-alice")
	bob := testUser("conf-bob")
	carol := testUser("conf-carol")
	for _, u := range []*model.User{alice, bob, carol} {
		mustStore(t, st.CreateUser(ctx, u))
	}

	t.Run("users", func(t *testing.T) {
		got, err := st.GetUserByID(ctx, alice.ID)
		if err != nil || got.Username != alice.Username {
			t.Fatalf("GetUserByID: %v %+v", err, got)
		}
		got, err = st.GetUserByUsername(ctx, alice.Username)
		if err != nil || got.ID != alice.ID {
			t.Fatalf("GetUserByUsername: %v %+v", err, got)
		}
		if _, err := st.GetUserByID(ctx, "missing-user"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("ожидался ErrNotFound, получен %v", err)
		}
		// Конфликт username — одинаковая ошибка в обоих хранилищах.
		dup := testUser("conf-alice")
		dup.ID = "id-conf-alice-dup"
		if err := st.CreateUser(ctx, dup); !errors.Is(err, ErrConflict) {
			t.Fatalf("ожидался ErrConflict, получен %v", err)
		}
	})

	t.Run("one-time prekeys", func(t *testing.T) {
		before, err := st.OneTimePrekeyCount(ctx, alice.ID)
		if err != nil {
			t.Fatal(err)
		}
		if before == 0 {
			t.Fatal("ожидались загруженные одноразовые ключи")
		}
		if _, err := st.TakeOneTimePrekey(ctx, alice.ID); err != nil {
			t.Fatal(err)
		}
		after, err := st.OneTimePrekeyCount(ctx, alice.ID)
		if err != nil {
			t.Fatal(err)
		}
		if after != before-1 {
			t.Fatalf("ключ должен расходоваться однократно: было %d, стало %d", before, after)
		}
	})

	t.Run("tokens", func(t *testing.T) {
		mustStore(t, st.PutToken(ctx, "conf-token", alice.ID, now.Add(time.Hour)))
		id, err := st.GetUserIDByTokenHash(ctx, "conf-token")
		if err != nil || id != alice.ID {
			t.Fatalf("GetUserIDByTokenHash: %q %v", id, err)
		}
		mustStore(t, st.DeleteToken(ctx, "conf-token"))
		if _, err := st.GetUserIDByTokenHash(ctx, "conf-token"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("отозванный токен остался жив: %v", err)
		}
		// Истёкший токен недействителен сразу, без фоновой чистки.
		mustStore(t, st.PutToken(ctx, "conf-expired", alice.ID, now.Add(-time.Minute)))
		if _, err := st.GetUserIDByTokenHash(ctx, "conf-expired"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("истёкший токен принят: %v", err)
		}
	})

	t.Run("profile and avatar", func(t *testing.T) {
		mustStore(t, st.UpdateAccountProfile(ctx, alice.ID, "conf-alice2", "Алиса", "Селезнёва"))
		got, err := st.GetUserByID(ctx, alice.ID)
		if err != nil || got.Username != "conf-alice2" {
			t.Fatalf("профиль не обновился: %+v %v", got, err)
		}
		if err := st.UpdateAccountProfile(ctx, bob.ID, "conf-alice2", "", ""); !errors.Is(err, ErrConflict) {
			t.Fatalf("занятый username должен давать ErrConflict, получен %v", err)
		}
		if _, err := st.GetAvatar(ctx, alice.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("аватара ещё нет, ожидался ErrNotFound, получен %v", err)
		}
	})

	t.Run("messages", func(t *testing.T) {
		msg := &model.Message{
			ID:          "conf-msg-1",
			SenderID:    alice.ID,
			RecipientID: bob.ID,
			Ciphertext:  []byte("envelope-1"),
			CreatedAt:   now,
		}
		mustStore(t, st.SaveMessage(ctx, msg))
		inbox, err := st.ListMessages(ctx, bob.ID, now.Add(-time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		if len(inbox) != 1 || inbox[0].ID != "conf-msg-1" {
			t.Fatalf("получатель должен видеть своё сообщение: %+v", inbox)
		}
		if string(inbox[0].Ciphertext) != "envelope-1" {
			t.Fatalf("конверт искажён: %q", inbox[0].Ciphertext)
		}
		foreign, err := st.ListMessages(ctx, carol.ID, now.Add(-time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		if len(foreign) != 0 {
			t.Fatalf("посторонний видит чужую переписку: %+v", foreign)
		}
		// Постраничная выборка видит те же записи.
		page, err := st.ListMessagesPage(ctx, bob.ID, now.Add(-time.Hour), "", 10)
		if err != nil {
			t.Fatal(err)
		}
		if len(page) != 1 {
			t.Fatalf("ListMessagesPage: ожидалось 1 сообщение, получено %d", len(page))
		}
	})

	t.Run("chats and members", func(t *testing.T) {
		chat := &model.Chat{ID: "conf-chat", Type: model.ChatGroup, Title: "Семья", CreatedBy: alice.ID, CreatedAt: now}
		mustStore(t, st.CreateChat(ctx, chat))
		// Создатель сразу владелец — без отдельного AddMember.
		owner, err := st.GetMember(ctx, chat.ID, alice.ID)
		if err != nil || owner.Role != model.RoleOwner {
			t.Fatalf("создатель должен быть owner: %+v %v", owner, err)
		}
		mustStore(t, st.AddMember(ctx, chat.ID, bob.ID, model.RoleMember))
		members, err := st.ListMembers(ctx, chat.ID)
		if err != nil || len(members) != 2 {
			t.Fatalf("ожидалось два участника: %d %v", len(members), err)
		}
		chats, err := st.ListChatsForUser(ctx, bob.ID)
		if err != nil || len(chats) != 1 || chats[0].ID != chat.ID {
			t.Fatalf("участник не видит чат: %+v %v", chats, err)
		}
		// Групповое сообщение видно всем участникам и никому вне чата.
		mustStore(t, st.SaveMessage(ctx, &model.Message{
			ID: "conf-msg-group", SenderID: alice.ID, ChatID: chat.ID,
			Ciphertext: []byte("group-envelope"), CreatedAt: now,
		}))
		groupInbox, err := st.ListMessages(ctx, bob.ID, now.Add(-time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		if !containsMessage(groupInbox, "conf-msg-group") {
			t.Fatalf("участник не получил групповое сообщение: %+v", groupInbox)
		}
		outsider, err := st.ListMessages(ctx, carol.ID, now.Add(-time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		if containsMessage(outsider, "conf-msg-group") {
			t.Fatal("неучастник получил групповое сообщение")
		}
	})

	t.Run("contacts", func(t *testing.T) {
		mustStore(t, st.AddContact(ctx, alice.ID, bob.ID))
		// Повторное добавление идемпотентно.
		mustStore(t, st.AddContact(ctx, alice.ID, bob.ID))
		contacts, err := st.ListContacts(ctx, alice.ID)
		if err != nil || len(contacts) != 1 || contacts[0] != bob.ID {
			t.Fatalf("контакты: %+v %v", contacts, err)
		}
	})

	t.Run("presence", func(t *testing.T) {
		seen := now.Add(-2 * time.Minute)
		mustStore(t, st.TouchPresence(ctx, bob.ID, seen))
		at, hidden, err := st.GetPresence(ctx, bob.ID)
		if err != nil || hidden {
			t.Fatalf("GetPresence: %v hidden=%v", err, hidden)
		}
		if at.IsZero() {
			t.Fatal("время последнего визита не сохранено")
		}
		mustStore(t, st.SetPresenceHidden(ctx, bob.ID, true))
		if _, hidden, err = st.GetPresence(ctx, bob.ID); err != nil || !hidden {
			t.Fatalf("скрытие не сохранилось: %v hidden=%v", err, hidden)
		}
		mustStore(t, st.SetPresenceHidden(ctx, bob.ID, false))
		if _, _, err := st.GetPresence(ctx, "missing-user"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("ожидался ErrNotFound, получен %v", err)
		}
	})

	t.Run("read cursors", func(t *testing.T) {
		later := now
		earlier := now.Add(-time.Hour)
		mustStore(t, st.SetReadCursor(ctx, bob.ID, alice.ID, later))
		// Курсор не едет назад: второе устройство не должно «разчитывать» чат.
		mustStore(t, st.SetReadCursor(ctx, bob.ID, alice.ID, earlier))
		got, err := st.GetReadCursor(ctx, bob.ID, alice.ID)
		if err != nil {
			t.Fatal(err)
		}
		if got.Before(later.Add(-time.Second)) {
			t.Fatalf("курсор уехал назад: %v вместо %v", got, later)
		}
		// Ничего не прочитано — нулевое время, а не ошибка.
		empty, err := st.GetReadCursor(ctx, carol.ID, alice.ID)
		if err != nil || !empty.IsZero() {
			t.Fatalf("ожидалось нулевое время: %v %v", empty, err)
		}
		if _, err := st.GetReadCursor(ctx, "missing-user", alice.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("ожидался ErrNotFound, получен %v", err)
		}
	})

	t.Run("chat reads", func(t *testing.T) {
		mustStore(t, st.SetChatRead(ctx, "conf-chat", bob.ID, now))
		mustStore(t, st.SetChatRead(ctx, "conf-chat", bob.ID, now.Add(-time.Hour)))
		reads, err := st.ListChatReads(ctx, "conf-chat")
		if err != nil {
			t.Fatal(err)
		}
		at, ok := reads[bob.ID]
		if !ok {
			t.Fatalf("прочтение участника не сохранено: %+v", reads)
		}
		if at.Before(now.Add(-time.Second)) {
			t.Fatalf("курсор чата уехал назад: %v", at)
		}
		if _, ok := reads[carol.ID]; ok {
			t.Fatal("в списке оказался тот, кто ничего не читал")
		}
		if _, err := st.ListChatReads(ctx, "missing-chat"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("ожидался ErrNotFound, получен %v", err)
		}
	})

	t.Run("calls", func(t *testing.T) {
		call := &model.Call{
			ID: "conf-call", CallerID: alice.ID, CalleeID: bob.ID,
			Participants: []string{alice.ID, bob.ID},
			Status:       model.CallRinging, CreatedAt: now.Add(-time.Hour),
		}
		mustStore(t, st.SaveCall(ctx, call))
		got, err := st.GetCall(ctx, call.ID)
		if err != nil || got.Status != model.CallRinging {
			t.Fatalf("GetCall: %+v %v", got, err)
		}
		list, err := st.ListCallsForUser(ctx, bob.ID)
		if err != nil || len(list) == 0 {
			t.Fatalf("вызываемый не видит звонок: %+v %v", list, err)
		}
		// Забытый ringing закрывается как missed, и повторный вызов его не трогает.
		expired, err := st.ExpireRingingCalls(ctx, now.Add(-time.Minute))
		if err != nil {
			t.Fatal(err)
		}
		if !containsString(expired, call.ID) {
			t.Fatalf("забытый вызов не закрыт: %+v", expired)
		}
		got, err = st.GetCall(ctx, call.ID)
		if err != nil || got.Status != model.CallMissed {
			t.Fatalf("ожидался статус missed: %+v %v", got, err)
		}
		again, err := st.ExpireRingingCalls(ctx, now.Add(-time.Minute))
		if err != nil || len(again) != 0 {
			t.Fatalf("повторное закрытие вернуло %+v %v", again, err)
		}
	})

	t.Run("push devices", func(t *testing.T) {
		mustStore(t, st.SavePushDevice(ctx, alice.ID, "conf-token-device", "android"))
		// Идемпотентность: повторная запись не плодит дубли.
		mustStore(t, st.SavePushDevice(ctx, alice.ID, "conf-token-device", "android"))
		devices, err := st.ListPushDevices(ctx, alice.ID)
		if err != nil || len(devices) != 1 {
			t.Fatalf("ожидалось одно устройство: %+v %v", devices, err)
		}
		// Тот же телефон с другим аккаунтом — токен переезжает.
		mustStore(t, st.SavePushDevice(ctx, bob.ID, "conf-token-device", "android"))
		devices, err = st.ListPushDevices(ctx, alice.ID)
		if err != nil || len(devices) != 0 {
			t.Fatalf("токен остался у старого владельца: %+v %v", devices, err)
		}
		mustStore(t, st.DeletePushDevice(ctx, "conf-token-device"))
		devices, err = st.ListPushDevices(ctx, bob.ID)
		if err != nil || len(devices) != 0 {
			t.Fatalf("устройство не удалено: %+v %v", devices, err)
		}
	})

	t.Run("account transfer", func(t *testing.T) {
		mustStore(t, st.PutAccountTransfer(ctx, carol.ID, "conf-hash-a", []byte("vault-a"), now.Add(time.Minute)))
		// Новый код отзывает предыдущий.
		mustStore(t, st.PutAccountTransfer(ctx, carol.ID, "conf-hash-b", []byte("vault-b"), now.Add(time.Minute)))
		if _, _, err := st.TakeAccountTransfer(ctx, "conf-hash-a"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("старый код не отозван: %v", err)
		}
		userID, vault, err := st.TakeAccountTransfer(ctx, "conf-hash-b")
		if err != nil || userID != carol.ID || string(vault) != "vault-b" {
			t.Fatalf("TakeAccountTransfer: %q %q %v", userID, vault, err)
		}
		// Код одноразовый.
		if _, _, err := st.TakeAccountTransfer(ctx, "conf-hash-b"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("код сработал дважды: %v", err)
		}
		// Истёкший код не работает.
		mustStore(t, st.PutAccountTransfer(ctx, carol.ID, "conf-hash-c", []byte("vault-c"), now.Add(-time.Minute)))
		if _, _, err := st.TakeAccountTransfer(ctx, "conf-hash-c"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("истёкший код принят: %v", err)
		}
	})

	t.Run("delete user", func(t *testing.T) {
		victim := testUser("conf-victim")
		mustStore(t, st.CreateUser(ctx, victim))
		mustStore(t, st.PutToken(ctx, "conf-victim-token", victim.ID, now.Add(time.Hour)))
		mustStore(t, st.SaveMessage(ctx, &model.Message{
			ID: "conf-msg-victim", SenderID: victim.ID, RecipientID: alice.ID,
			Ciphertext: []byte("bye"), CreatedAt: now,
		}))
		mustStore(t, st.DeleteUser(ctx, victim.ID))
		if _, err := st.GetUserByID(ctx, victim.ID); !errors.Is(err, ErrNotFound) {
			t.Fatalf("пользователь остался: %v", err)
		}
		if _, err := st.GetUserIDByTokenHash(ctx, "conf-victim-token"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("токен удалённого аккаунта жив: %v", err)
		}
		left, err := st.ListMessages(ctx, alice.ID, now.Add(-time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		if containsMessage(left, "conf-msg-victim") {
			t.Fatal("сообщения удалённого аккаунта остались")
		}
	})
}

func containsMessage(list []*model.Message, id string) bool {
	for _, m := range list {
		if m != nil && m.ID == id {
			return true
		}
	}
	return false
}

func containsString(list []string, want string) bool {
	for _, v := range list {
		if v == want {
			return true
		}
	}
	return false
}

// newPostgresContractStore поднимает одноразовую схему с применёнными
// миграциями. Список миграций берётся glob'ом и не захардкожен по числу:
// иначе каждая новая миграция ломает тест.
func newPostgresContractStore(t *testing.T, prefix string) (*PostgresStore, func()) {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set; requires a disposable test database")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	schema := fmt.Sprintf("%s_%d", prefix, time.Now().UnixNano())
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+quoted); err != nil {
		admin.Close(context.Background())
		cancel()
		t.Fatal(err)
	}
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	paths, err := filepath.Glob("../../migrations/*.sql")
	if err != nil || len(paths) == 0 {
		t.Fatalf("migration files: %v %v", paths, err)
	}
	for _, path := range paths {
		sqlBytes, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := pool.Exec(ctx, string(sqlBytes)); err != nil {
			t.Fatalf("%s: %v", path, err)
		}
	}
	cleanup := func() {
		pool.Close()
		drop, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		if _, err := admin.Exec(drop, "DROP SCHEMA "+quoted+" CASCADE"); err != nil {
			t.Error(err)
		}
		admin.Close(context.Background())
		cancel()
	}
	return &PostgresStore{pool: pool}, cleanup
}
