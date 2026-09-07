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
	if err != nil || len(paths) != 6 { t.Fatalf("migration files: %v %v", paths, err) }
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
