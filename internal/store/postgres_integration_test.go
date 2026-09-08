package store

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func TestPostgresReliability(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" { t.Skip("TEST_DATABASE_URL not set; requires a disposable test database") }
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil { t.Fatal(err) }
	defer admin.Close(context.Background())
	schema := fmt.Sprintf("umbra_test_%d", time.Now().UnixNano())
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err := admin.Exec(ctx, "CREATE SCHEMA " + quoted); err != nil { t.Fatal(err) }
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		if _, err := admin.Exec(cleanup, "DROP SCHEMA " + quoted + " CASCADE"); err != nil { t.Error(err) }
	}()
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil { t.Fatal(err) }
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil { t.Fatal(err) }
	defer pool.Close()
	paths, err := filepath.Glob("../../migrations/*.sql")
	if err != nil || len(paths) != 8 { t.Fatalf("migration files: %v %v", paths, err) }
	for _, path := range paths {
		sql, err := os.ReadFile(path)
		if err != nil { t.Fatal(err) }
		if _, err := pool.Exec(ctx, string(sql)); err != nil { t.Fatalf("%s: %v", path, err) }
	}
	st := &PostgresStore{pool: pool}
	reliabilityContract(t, st)
	var orphans int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM messages WHERE chat_id='group'").Scan(&orphans); err != nil { t.Fatal(err) }
	if orphans != 0 { t.Fatalf("deleted group left %d messages", orphans) }
}

// TestPostgresAccountTransfer проверяет хранилище одноразовых кодов переноса:
// отзыв предыдущего кода, однократное использование, истечение и очистку.
func TestPostgresAccountTransfer(t *testing.T) {
	dsn := os.Getenv("TEST_DATABASE_URL")
	if dsn == "" { t.Skip("TEST_DATABASE_URL not set; requires a disposable test database") }
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	admin, err := pgx.Connect(ctx, dsn)
	if err != nil { t.Fatal(err) }
	defer admin.Close(context.Background())
	schema := fmt.Sprintf("umbra_transfer_%d", time.Now().UnixNano())
	quoted := pgx.Identifier{schema}.Sanitize()
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+quoted); err != nil { t.Fatal(err) }
	defer func() {
		cleanup, stop := context.WithTimeout(context.Background(), 10*time.Second)
		defer stop()
		_, _ = admin.Exec(cleanup, "DROP SCHEMA "+quoted+" CASCADE")
	}()
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil { t.Fatal(err) }
	cfg.ConnConfig.RuntimeParams["search_path"] = schema
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil { t.Fatal(err) }
	defer pool.Close()
	for _, path := range []string{"../../migrations/001_init.sql", "../../migrations/002_media.sql", "../../migrations/003_groups.sql", "../../migrations/004_calls.sql", "../../migrations/005_secret_chats.sql", "../../migrations/006_integrity.sql", "../../migrations/007_phone.sql", "../../migrations/008_account_transfer.sql"} {
		sqlBytes, err := os.ReadFile(path)
		if err != nil { t.Fatal(err) }
		if _, err := pool.Exec(ctx, string(sqlBytes)); err != nil { t.Fatalf("%s: %v", path, err) }
	}
	st := &PostgresStore{pool: pool}

	// Пользователь для FK.
	userID := "transfer-user"
	key := []byte("0123456789abcdef0123456789abcdef")
	if _, err := pool.Exec(ctx,
		`INSERT INTO users(id,username,identity_ed25519,identity_x25519,signed_prekey,signed_prekey_sig)
		 VALUES ($1,$2,$3,$4,$5,$6)`,
		userID, "transferuser", key, key, key, key); err != nil {
		t.Fatal(err)
	}

	future := time.Now().Add(time.Minute)
	past := time.Now().Add(-time.Minute)

	if err := st.PutAccountTransfer(ctx, userID, "hash-a", []byte("vault-a"), future); err != nil { t.Fatal(err) }
	// Новый код пользователя отзывает прежний неиспользованный.
	if err := st.PutAccountTransfer(ctx, userID, "hash-b", []byte("vault-b"), future); err != nil { t.Fatal(err) }
	if _, _, err := st.TakeAccountTransfer(ctx, "hash-a"); err != ErrNotFound {
		t.Fatalf("старый код должен быть отозван, got %v", err)
	}
	gotUser, vault, err := st.TakeAccountTransfer(ctx, "hash-b")
	if err != nil { t.Fatal(err) }
	if gotUser != userID || string(vault) != "vault-b" { t.Fatalf("unexpected: %s %s", gotUser, vault) }
	// Код одноразовый.
	if _, _, err := st.TakeAccountTransfer(ctx, "hash-b"); err != ErrNotFound {
		t.Fatalf("использованный код должен быть недействителен, got %v", err)
	}
	// Истёкший код не отдаётся.
	if err := st.PutAccountTransfer(ctx, userID, "hash-c", []byte("vault-c"), past); err != nil { t.Fatal(err) }
	if _, _, err := st.TakeAccountTransfer(ctx, "hash-c"); err != ErrNotFound {
		t.Fatalf("истёкший код должен быть недействителен, got %v", err)
	}

	// PurgeExpired вычищает использованные и истёкшие.
	if err := st.PurgeExpired(ctx, time.Now()); err != nil { t.Fatal(err) }
	var left int
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM account_transfers").Scan(&left); err != nil { t.Fatal(err) }
	if left != 0 { t.Fatalf("ожидался 0 записей после PurgeExpired, осталось %d", left) }

	// DeleteUser каскадно удаляет и переносы.
	if err := st.PutAccountTransfer(ctx, userID, "hash-d", []byte("vault-d"), future); err != nil { t.Fatal(err) }
	if err := st.DeleteUser(ctx, userID); err != nil { t.Fatal(err) }
	if err := pool.QueryRow(ctx, "SELECT count(*) FROM account_transfers").Scan(&left); err != nil { t.Fatal(err) }
	if left != 0 { t.Fatalf("каскад не удалил переносы, осталось %d", left) }
}
