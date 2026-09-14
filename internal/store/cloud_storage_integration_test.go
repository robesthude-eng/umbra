package store

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"umbra/server/internal/cloudcrypto"
	"umbra/server/internal/model"
)

func cloudPostgres(t *testing.T) (*PostgresStore, context.Context) {
	t.Helper()
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("TEST_DATABASE_URL not set; requires a disposable database")
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	t.Cleanup(cancel)
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := fmt.Sprintf("umbra_cloud_%d", time.Now().UnixNano())
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+quoted); err != nil {
		admin.Close(ctx)
		t.Fatal(err)
	}
	t.Cleanup(func() {
		clean, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		if _, err := admin.Exec(clean, "DROP SCHEMA "+quoted+" CASCADE"); err != nil {
			t.Error(err)
		}
		admin.Close(clean)
	})
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		t.Fatal(err)
	}
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	// Also explicit for PostgreSQL wire adapters that ignore startup parameters.
	cfg.AfterConnect = func(ctx context.Context, c *pgx.Conn) error {
		_, err := c.Exec(ctx, "SET search_path TO "+quoted)
		return err
	}
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	paths, err := filepath.Glob("../../migrations/*.sql")
	if err != nil || len(paths) != 16 {
		t.Fatal("migrations", paths, err)
	}
	for _, path := range paths {
		sql, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := pool.Exec(ctx, string(sql)); err != nil {
			t.Fatalf("%s: %v", path, err)
		}
	}
	return &PostgresStore{pool: pool}, ctx
}

func TestPostgresCloudStorage(t *testing.T) {
	st, ctx := cloudPostgres(t)
	for _, name := range []string{"owner", "peer"} {
		mustStore(t, st.CreateUser(ctx, testUser(name)))
	}
	now := time.Now().UTC().Truncate(time.Microsecond)
	legacy := model.Message{ID: "legacy", SenderID: "id-owner", RecipientID: "id-peer", ClientID: "old-request", Ciphertext: []byte("old private message"), CreatedAt: now}
	mustStore(t, st.SaveMessage(ctx, &legacy))
	keys, err := cloudcrypto.Generate(nil)
	mustStore(t, err)
	mustStore(t, st.EnableCloudStorage(ctx, keys))
	fresh := model.Message{ID: "fresh", SenderID: "id-owner", RecipientID: "id-peer", ClientID: "new-request", Ciphertext: []byte("new private message"), CreatedAt: now.Add(time.Second)}
	mustStore(t, st.SaveMessage(ctx, &fresh))
	var stored, digest []byte
	var format int
	mustStore(t, st.pool.QueryRow(ctx, `SELECT ciphertext,storage_format FROM messages WHERE id='fresh'`).Scan(&stored, &format))
	if format != 1 || bytes.Contains(stored, fresh.Ciphertext) {
		t.Fatal("unencrypted message at rest")
	}
	mustStore(t, st.pool.QueryRow(ctx, `SELECT request_hash FROM message_receipts WHERE client_id='new-request'`).Scan(&digest))
	hash := messageRequestHash(&fresh)
	if len(digest) == 32 || bytes.Contains(digest, hash[:]) {
		t.Fatal("guessable receipt hash at rest")
	}
	page, err := st.ListMessagesPage(ctx, "id-peer", now.Add(-time.Second), "", 200)
	if err != nil || len(page) != 2 || !bytes.Equal(page[0].Ciphertext, legacy.Ciphertext) || !bytes.Equal(page[1].Ciphertext, fresh.Ciphertext) {
		t.Fatal("mixed history", page, err)
	}
	for {
		n, err := st.MigrateMessageBatch(ctx)
		mustStore(t, err)
		if n == 0 {
			break
		}
	}
	status, err := st.CloudStorageStatus(ctx)
	mustStore(t, err)
	if status != (CloudStorageStatus{}) {
		t.Fatal("legacy data remains", status)
	}
	next, err := cloudcrypto.Generate(keys)
	mustStore(t, err)
	// Recreate the store as after process restart; the database stores only IDs.
	restarted := &PostgresStore{pool: st.pool}
	mustStore(t, restarted.EnableCloudStorage(ctx, next))
	for _, original := range []model.Message{legacy, fresh} {
		retry := original
		retry.ID = "retry"
		retry.CreatedAt = now.Add(time.Hour)
		mustStore(t, restarted.SaveMessage(ctx, &retry))
		if retry.ID != original.ID || !retry.CreatedAt.Equal(original.CreatedAt) {
			t.Fatal("retry lost original acknowledgement")
		}
		retry.Ciphertext = []byte("different message")
		if err := restarted.SaveMessage(ctx, &retry); !errors.Is(err, ErrConflict) {
			t.Fatal("conflicting retry accepted", err)
		}
	}
	page, err = restarted.ListMessages(ctx, "id-peer", now.Add(-time.Second))
	if err != nil || len(page) != 2 || !bytes.Equal(page[0].Ciphertext, legacy.Ciphertext) {
		t.Fatal("rotated history", err)
	}
	wrong, err := cloudcrypto.Generate(nil)
	mustStore(t, err)
	if err := (&PostgresStore{pool: st.pool}).EnableCloudStorage(ctx, wrong); err == nil {
		t.Fatal("unrelated keys accepted at startup")
	}
	if err := (&PostgresStore{pool: st.pool}).EnableCloudStorage(ctx, keys); err == nil {
		t.Fatal("missing new key accepted at startup")
	}
	// Ciphertext cannot be reassigned to a different message or addressing context.
	_, err = st.pool.Exec(ctx, `UPDATE messages SET ciphertext=$1 WHERE id='legacy'`, stored)
	mustStore(t, err)
	if _, err := restarted.ListMessagesPage(ctx, "id-peer", now.Add(-time.Second), "", 200); err == nil {
		t.Fatal("substituted message accepted")
	}
}

func TestPostgresMigratedMediaReferencesAndDeletion(t *testing.T) {
	st, ctx := cloudPostgres(t)
	mustStore(t, st.CreateUser(ctx, testUser("owner")))
	mustStore(t, st.SaveMediaWithQuota(ctx, &model.Media{ID: "public", BlobID: "old-physical", OwnerID: "id-owner", Size: 42, ContentType: "audio/mp4", CreatedAt: time.Now()}, 100))
	mustStore(t, st.CommitEncryptedMedia(ctx, "public", "old-physical", "encrypted-object"))
	m, err := st.GetMedia(ctx, "public")
	if err != nil || m.ObjectID() != "encrypted-object" || m.StorageFormat != 1 || m.Size != 42 {
		t.Fatal("metadata", m, err)
	}
	for id, want := range map[string]bool{"public": false, "old-physical": false, "encrypted-object": true} {
		got, err := st.BlobReferenced(ctx, id)
		if err != nil || got != want {
			t.Fatal("physical reference", id, got, err)
		}
	}
	used, err := st.MediaBytesForUser(ctx, "id-owner")
	if err != nil || used != 42 {
		t.Fatal("quota changed", used, err)
	}
	mustStore(t, st.DeleteUser(ctx, "id-owner"))
	ids, err := st.PendingBlobDeletes(ctx)
	if err != nil || !slices.Contains(ids, "old-physical") || !slices.Contains(ids, "encrypted-object") || slices.Contains(ids, "public") {
		t.Fatal("physical deletion queue", ids, err)
	}
}
