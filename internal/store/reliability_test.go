package store

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"umbra/server/internal/model"
)

func TestMemoryReliability(t *testing.T) { reliabilityContract(t, NewMemoryStore()) }

// Тот же контракт запускается на PostgreSQL в postgres_integration_test.go.
func reliabilityContract(t *testing.T, st Store) {
	t.Helper()
	ctx := context.Background()
	for _, name := range []string{"owner", "member", "peer"} { mustStore(t, st.CreateUser(ctx, testUser(name))) }
	now := time.Now().UTC().Truncate(time.Microsecond)

	t.Run("concurrent quota", func(t *testing.T) {
		var wg sync.WaitGroup
		results := make(chan error, 32)
		for i := 0; i < 32; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				results <- st.SaveMediaWithQuota(ctx, &model.Media{ID: fmt.Sprintf("media-%d", i),
					OwnerID: "id-member", Size: 2, ContentType: "application/octet-stream", CreatedAt: now}, 10)
			}(i)
		}
		wg.Wait()
		close(results)
		accepted := 0
		for err := range results {
			if err == nil { accepted++ } else if !errors.Is(err, ErrQuota) { t.Fatal(err) }
		}
		used, err := st.MediaBytesForUser(ctx, "id-member")
		if err != nil || used != 10 || accepted != 5 { t.Fatalf("quota: used=%d accepted=%d error=%v", used, accepted, err) }
	})

	t.Run("idempotent send and expiry", func(t *testing.T) {
		expires := now.Add(time.Minute)
		request := model.Message{ID: "original", ClientID: "request-1", SenderID: "id-owner", RecipientID: "id-peer",
			Ciphertext: []byte("opaque"), CreatedAt: now, ExpiresAt: &expires, ExpiresIn: 60}
		mustStore(t, st.SaveMessage(ctx, &request))
		var wg sync.WaitGroup
		for i := 0; i < 16; i++ {
			wg.Add(1)
			go func(i int) {
				defer wg.Done()
				retry := request
				retry.ID = fmt.Sprintf("retry-%d", i)
				retry.CreatedAt = now.Add(time.Second)
				if err := st.SaveMessage(ctx, &retry); err != nil { t.Error(err); return }
				if retry.ID != request.ID || !retry.CreatedAt.Equal(now) || !retry.ExpiresAt.Equal(expires) { t.Errorf("changed receipt: %#v", retry) }
			}(i)
		}
		wg.Wait()
		changed := request
		changed.Ciphertext = []byte("different")
		if err := st.SaveMessage(ctx, &changed); !errors.Is(err, ErrConflict) { t.Fatalf("conflicting retry: %v", err) }
		mustStore(t, st.PurgeExpired(ctx, expires.Add(time.Second)))
		mustStore(t, st.SaveMessage(ctx, &request))
		messages, err := st.ListMessages(ctx, "id-peer", time.Unix(0, 0))
		if err != nil || len(messages) != 0 { t.Fatalf("expired retry resurrected message: %v %v", messages, err) }
	})

	t.Run("stable pagination with equal timestamps", func(t *testing.T) {
		for _, id := range []string{"page-c", "page-a", "page-b"} {
			mustStore(t, st.SaveMessage(ctx, &model.Message{ID: id, SenderID: "id-owner", RecipientID: "id-peer", Ciphertext: []byte(id), CreatedAt: now}))
		}
		first, err := st.ListMessagesPage(ctx, "id-peer", now.Add(-time.Second), "", 2)
		if err != nil || len(first) != 2 || first[0].ID != "page-a" || first[1].ID != "page-b" { t.Fatalf("first page: %v %v", first, err) }
		second, err := st.ListMessagesPage(ctx, "id-peer", first[1].CreatedAt, first[1].ID, 2)
		if err != nil || len(second) != 1 || second[0].ID != "page-c" { t.Fatalf("second page: %v %v", second, err) }
	})

	t.Run("prekey publication retry does not replenish consumed keys", func(t *testing.T) {
		keys := testUser("owner")
		keys.KeyVersion, keys.KeyBundleID = 2, "batch-1"
		mustStore(t, st.UpdateKeys(ctx, keys.ID, keys))
		_, first, err := st.TakePrekeyBundle(ctx, keys.Username)
		mustStore(t, err)
		mustStore(t, st.UpdateKeys(ctx, keys.ID, keys))
		_, second, err := st.TakePrekeyBundle(ctx, keys.Username)
		mustStore(t, err)
		_, third, err := st.TakePrekeyBundle(ctx, keys.Username)
		if err != nil || string(first) == string(second) || len(third) != 0 { t.Fatalf("prekey reused: %q %q %q %v", first, second, third, err) }
		keys.KeyBundleID, keys.IdentityX25519 = "batch-2", []byte("different identity")
		if err := st.UpdateKeys(ctx, keys.ID, keys); !errors.Is(err, ErrConflict) { t.Fatalf("identity rotation: %v", err) }
	})

	t.Run("burn member preserves other authors and queues files", func(t *testing.T) {
		mustStore(t, st.CreateChat(ctx, newTestChat("group", model.ChatGroup, "id-owner")))
		mustStore(t, st.AddMember(ctx, "group", "id-member", model.RoleMember))
		mustStore(t, st.AddMember(ctx, "group", "id-peer", model.RoleMember))
		for _, user := range []string{"owner", "member", "peer"} {
			mustStore(t, st.SaveMessage(ctx, &model.Message{ID: "group-" + user, SenderID: "id-" + user,
				ChatID: "group", Ciphertext: []byte("opaque"), CreatedAt: now}))
		}
		mustStore(t, st.DeleteUser(ctx, "id-member"))
		messages, err := st.ListMessages(ctx, "id-owner", time.Unix(0, 0))
		if err != nil || len(messages) != 2 { t.Fatalf("foreign group history lost: %v %v", messages, err) }
		pending, err := st.PendingBlobDeletes(ctx)
		if err != nil || len(pending) != 5 { t.Fatalf("blob deletion queue: %v %v", pending, err) }
		mustStore(t, st.CompleteBlobDelete(ctx, pending[0]))
		pending, err = st.PendingBlobDeletes(ctx)
		if err != nil || len(pending) != 4 { t.Fatalf("completed blob still pending: %v %v", pending, err) }
		mustStore(t, st.DeleteUser(ctx, "id-owner"))
		if _, err := st.GetChat(ctx, "group"); !errors.Is(err, ErrNotFound) { t.Fatalf("owner's group survived: %v", err) }
	})
}

func mustStore(t *testing.T, err error) {
	t.Helper()
	if err != nil { t.Fatal(err) }
}
