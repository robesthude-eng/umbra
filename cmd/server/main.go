// Командная точка входа сервера Umbra.
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/httpapi"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

func main() {
	cfg := config.Load()

	var st store.Store
	var err error
	if cfg.Store == "postgres" {
		st, err = store.NewPostgresStore(context.Background(), cfg.DatabaseURL)
		if err != nil {
			log.Fatalf("не удалось подключиться к PostgreSQL: %v", err)
		}
		log.Printf("используется PostgreSQL")
	} else {
		st = store.NewMemoryStore()
		log.Printf("используется in-memory хранилище (для продакшна задайте STORE=postgres)")
	}
	defer st.Close()

	var blobs blobstore.BlobStore
	if cfg.BlobStoreType == "s3" {
		blobs, err = blobstore.NewS3BlobStore(cfg.S3Endpoint, cfg.S3AccessKey, cfg.S3SecretKey, cfg.S3Bucket, cfg.S3Region, cfg.S3UseSSL)
		log.Printf("используется S3-хранилище блобов (бакет %q)", cfg.S3Bucket)
	} else {
		blobs, err = blobstore.NewFileBlobStore(cfg.BlobDir)
		log.Printf("используется файловое хранилище блобов (%s)", cfg.BlobDir)
	}
	if err != nil {
		log.Fatalf("не удалось открыть хранилище медиа: %v", err)
	}
	defer blobs.Close()

	hub := ws.NewHub()
	go hub.Run()

	srv := httpapi.NewServerWithBlobStore(cfg, st, hub, blobs)

	go func() {
		log.Printf("Umbra сервер запущен на %s", cfg.ListenAddr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("ошибка сервера: %v", err)
		}
	}()

	// Сначала завершаем запросы, затем закрываем хранилища.
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Printf("завершение работы...")
	_ = srv.Shutdown(context.Background())
}
