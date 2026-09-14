// Package cloudstorage migrates existing server data without changing public IDs.
package cloudstorage

import (
	"bytes"
	"context"
	"crypto/sha256"
	"fmt"
	"io"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/cloudcrypto"
	"umbra/server/internal/crypto"
	"umbra/server/internal/maintenance"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

type MigrationStore interface {
	LockBlobs(context.Context, bool) (func(), error)
	MigrateMessageBatch(context.Context) (int, error)
	LegacyMedia(context.Context) ([]*model.Media, error)
	CommitEncryptedMedia(context.Context, string, string, string) error
}

type checkedReader struct {
	ctx context.Context
	io.Reader
	n int64
}

func (r *checkedReader) Read(p []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	n, err := r.Reader.Read(p)
	r.n += int64(n)
	return n, err
}

// Migrate is resumable. Stop all server instances during migration so old
// downloads and background deletion do not race the switch of physical blobs.
func Migrate(ctx context.Context, st MigrationStore, blobs blobstore.BlobStore, keys *cloudcrypto.Keyring) error {
	if !keys.Has(keys.ActiveID()) {
		return cloudcrypto.ErrInvalid
	}
	unlock, err := st.LockBlobs(ctx, true)
	if err != nil {
		return err
	}
	defer unlock()
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		n, err := st.MigrateMessageBatch(ctx)
		if err != nil {
			return err
		}
		if n == 0 {
			break
		}
	}
	for {
		if err := ctx.Err(); err != nil {
			return err
		}
		items, err := st.LegacyMedia(ctx)
		if err != nil {
			return err
		}
		if len(items) == 0 {
			break
		}
		for _, m := range items {
			if err := migrateMedia(ctx, st, blobs, keys, m); err != nil {
				return fmt.Errorf("migrate media %s: %w", m.ID, err)
			}
		}
	}
	// Old plaintext blobs enter the durable queue only in the pointer transaction.
	if full, ok := st.(store.Store); ok {
		for {
			pending, err := full.PendingBlobDeletes(ctx)
			if err != nil {
				return err
			}
			if len(pending) == 0 {
				break
			}
			if err := maintenance.DrainBlobs(ctx, full, blobs); err != nil {
				return err
			}
		}
	}
	return nil
}

func migrateMedia(ctx context.Context, st MigrationStore, blobs blobstore.BlobStore, keys *cloudcrypto.Keyring, m *model.Media) error {
	old, err := blobstore.Get(ctx, blobs, m.ObjectID())
	if err != nil {
		return err
	}
	defer old.Close()
	hash := sha256.New()
	source := &checkedReader{ctx: ctx, Reader: io.TeeReader(old, hash)}
	encrypted, err := keys.EncryptReader(source, m.ID)
	if err != nil {
		return err
	}
	newID, err := crypto.NewToken()
	if err != nil {
		return err
	}
	if err := blobstore.Put(ctx, blobs, newID, encrypted); err != nil {
		return err
	}
	// Keep originals on all failures. Unreferenced copies can be removed by GC.
	if source.n != m.Size {
		return fmt.Errorf("source size mismatch: expected %d, got %d", m.Size, source.n)
	}
	check, err := blobstore.Get(ctx, blobs, newID)
	if err != nil {
		return err
	}
	defer check.Close()
	plain, err := keys.DecryptReader(check, m.ID)
	if err != nil {
		return err
	}
	verifiedHash := sha256.New()
	n, err := io.Copy(verifiedHash, &checkedReader{ctx: ctx, Reader: plain})
	if err != nil {
		return err
	}
	if n != source.n || !bytes.Equal(verifiedHash.Sum(nil), hash.Sum(nil)) {
		return fmt.Errorf("encrypted copy verification failed")
	}
	// Never delete newID on an ambiguous commit error: the pointer may be live.
	return st.CommitEncryptedMedia(ctx, m.ID, m.ObjectID(), newID)
}
