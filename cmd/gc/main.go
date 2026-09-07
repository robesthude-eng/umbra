// GC — удаляет осиротевшие блобы (файлы/объекты без метаданных в БД).
// Такая ситуация возможна после аварийного завершения между записью blob
// и сохранением метаданных, либо после удаления метаданных («сжигание» аккаунта).
//
// Запуск (примеры):
//
//	STORE=postgres DATABASE_URL=... BLOB_DIR=./data/blobs go run ./cmd/gc
//	STORE=postgres DATABASE_URL=... BLOB_DIR=./data/blobs go run ./cmd/gc -min-age=1h
//	STORE=postgres DATABASE_URL=... BLOB_STORE_TYPE=s3 S3_ENDPOINT=... S3_BUCKET=... \
//	    go run ./cmd/gc
//
// По умолчанию сироты младше 24 часов не удаляются (льготный период -min-age):
// blob публикуется раньше, чем фиксируются метаданные, и параллельный запуск GC
// не должен удалить файл незавершённой загрузки.
//
// GC требует STORE=postgres: с in-memory хранилищем метаданных нет, и все блобы
// выглядели бы сиротами — запуск с STORE=memory аварийно прекращается.
package main

import (
	"context"
	"flag"
	"log"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/gc"
	"umbra/server/internal/store"
)

func main() {
	minAge := flag.Duration("min-age", gc.DefaultMinAge,
		"не удалять осиротевшие блобы младше этого возраста (0 — отключить льготный период)")
	flag.Parse()

	cfg := config.Load()

	if cfg.Store != "postgres" {
		log.Fatalf("GC требует STORE=postgres и DATABASE_URL: с %q метаданных нет и все блобы выглядят сиротами", cfg.Store)
	}
	st, err := store.NewPostgresStore(context.Background(), cfg.DatabaseURL)
	if err != nil {
		log.Fatalf("не удалось открыть хранилище: %v", err)
	}
	defer st.Close()

	var blobs blobstore.BlobStore
	if cfg.BlobStoreType == "s3" {
		blobs, err = blobstore.NewS3BlobStore(cfg.S3Endpoint, cfg.S3AccessKey, cfg.S3SecretKey, cfg.S3Bucket, cfg.S3Region, cfg.S3UseSSL)
	} else {
		blobs, err = blobstore.NewFileBlobStore(cfg.BlobDir)
	}
	if err != nil {
		log.Fatalf("не удалось открыть blobstore: %v", err)
	}
	defer blobs.Close()

	stats, err := gc.RemoveOrphans(context.Background(), st, blobs, *minAge, time.Now())
	if err != nil {
		log.Fatalf("GC: %v", err)
	}
	log.Printf("готово: перечислено %d, удалено %d, пропущено свежих %d, ошибок %d",
		stats.Listed, stats.Removed, stats.Skipped, stats.Errors)
}
