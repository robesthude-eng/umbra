// GC по умолчанию только показывает сироты. Удаление требует явного -delete.
package main

import (
	"context"
	"errors"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
    "time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/store"
)

func main() {
	remove := flag.Bool("delete",false,"удалить найденные сироты (по умолчанию dry-run)")
	flag.Parse()
	cfg:=config.Load()
    if err := cfg.Validate(); err != nil { log.Fatal(err) }
	if cfg.Store!="postgres" || cfg.DatabaseURL=="" {
		log.Fatal("GC запрещён без PostgreSQL: отдельный memory-процесс не видит метаданные сервера")
	}
	ctx,cancel:=signal.NotifyContext(context.Background(),os.Interrupt,syscall.SIGTERM)
	defer cancel()
	st,err:=store.NewPostgresStore(ctx,cfg.DatabaseURL)
	if err!=nil { log.Fatal(err) }
	defer st.Close()
	var blobs blobstore.BlobStore
	if cfg.BlobStoreType=="s3" {
		blobs,err=blobstore.NewS3BlobStore(cfg.S3Endpoint,cfg.S3AccessKey,cfg.S3SecretKey,cfg.S3Bucket,cfg.S3Region,cfg.S3UseSSL)
	} else { blobs,err=blobstore.NewFileBlobStore(cfg.BlobDir) }
	if err!=nil { log.Fatal(err) }
	defer blobs.Close()
	if err:=collect(ctx,st,blobs,*remove); err!=nil { log.Fatal(err) }
}

func collect(ctx context.Context, st store.Store, blobs blobstore.BlobStore, remove bool) error {
	// Блокировка общая с загрузками во всех новых экземплярах сервера.
	unlock,err:=st.LockBlobs(ctx,true)
	if err!=nil { return err }
	defer unlock()
	ids,err:=blobstore.List(ctx, blobs)
	if err!=nil { return err }
    timestamps, ok := blobs.(blobstore.TimestampReader)
    if !ok { return errors.New("blobstore cannot verify object age") }
    cutoff := time.Now().Add(-24*time.Hour)
	for _,id:=range ids {
		if err:=ctx.Err(); err!=nil { return err }
		_,err:=st.GetMedia(ctx,id)
		if err==nil { continue }
		if !errors.Is(err,store.ErrNotFound) { return err }
        modified, err := timestamps.ModifiedAt(ctx, id)
        if errors.Is(err, store.ErrNotFound) { continue }
        if err != nil { return err }
        // Запас защищает недавние загрузки, в том числе при потере lock-соединения.
        if modified.IsZero() || modified.After(cutoff) { log.Printf("skip recent object %s", id); continue }
		if !remove { log.Printf("dry-run: orphan %s",id); continue }
		if err:=blobstore.Delete(ctx,blobs,id); err!=nil && !errors.Is(err,store.ErrNotFound) { return err }
		log.Printf("removed orphan %s",id)
	}
	return nil
}
