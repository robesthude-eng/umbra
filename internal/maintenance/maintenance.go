// Package maintenance удаляет истёкшие данные и обрабатывает durable-очередь файлов.
package maintenance

import (
	"context"
	"errors"
	"log"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/store"
)

func DrainBlobs(ctx context.Context, st store.Store, blobs blobstore.BlobStore) error {
	if blobs == nil { return nil }
	ids, err := st.PendingBlobDeletes(ctx)
	if err != nil { return err }
	var result error
	for _, id := range ids {
		if err := ctx.Err(); err != nil { return err }
		if err := blobstore.Delete(ctx, blobs, id); err != nil && !errors.Is(err,store.ErrNotFound) {
			result=errors.Join(result,err); continue
		}
		if err := st.CompleteBlobDelete(ctx,id); err != nil { result=errors.Join(result,err) }
	}
	return result
}

func Run(ctx context.Context, st store.Store, blobs blobstore.BlobStore) {
	ticker := time.NewTicker(10*time.Second)
	defer ticker.Stop()
	for {
		sweep, cancel := context.WithTimeout(ctx,30*time.Second)
		if err := st.PurgeExpired(sweep,time.Now().UTC()); err != nil && ctx.Err()==nil { log.Printf("maintenance: %v",err) }
		if err := DrainBlobs(sweep,st,blobs); err != nil && ctx.Err()==nil { log.Printf("blob cleanup pending: %v",err) }
		cancel()
		select { case <-ctx.Done(): return; case <-ticker.C: }
	}
}
