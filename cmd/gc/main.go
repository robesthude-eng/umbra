// GC — удаляет осиротевшие блобы (файлы/объекты без метаданных в БД).
// Такая ситуация возможна после аварийного завершения между записью blob
// и сохранением метаданных, либо после ручного удаления метаданных.
//
// Запуск (примеры):
//
//	BLOB_DIR=./data/blobs go run ./cmd/gc        # файловое хранилище
//	STORE=postgres DATABASE_URL=... BLOB_STORE_TYPE=s3 S3_ENDPOINT=... S3_BUCKET=... \
//	    go run ./cmd/gc                          # S3 + PostgreSQL
package main

import (
	"context"
	"errors"
	"log"
	"os"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/store"
)

func main() {
	cfg := config.Load()

	var st store.Store
	var err error
	if cfg.Store == "postgres" {
		st, err = store.NewPostgresStore(context.Background(), cfg.DatabaseURL)
	} else {
		st = store.NewMemoryStore()
		log.Printf("внимание: STORE=memory — метаданных нет, GC не сможет определить сирот")
	}
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

	lister, ok := blobs.(blobstore.Lister)
	if !ok {
		log.Fatalf("blobstore не поддерживает перечисление")
	}

	ids, err := lister.List()
	if err != nil {
		log.Fatalf("перечисление блобов: %v", err)
	}
	log.Printf("блобов найдено: %d", len(ids))

	ctx := context.Background()
	removed := 0
	for _, id := range ids {
		if _, err := st.GetMedia(ctx, id); err == nil {
			continue // есть метаданные — не сирота
		} else if !errors.Is(err, store.ErrNotFound) {
			log.Printf("пропуск %s: %v", id, err)
			continue
		}
		// Сирота: метаданных нет, удаляем blob.
		if err := blobs.Delete(id); err != nil {
			log.Printf("не удалось удалить %s: %v", id, err)
			continue
		}
		removed++
		log.Printf("удалён осиротевший blob: %s", id)
	}
	log.Printf("готово: удалено %d осиротевших блобов", removed)
	_ = os.Stdout
}
