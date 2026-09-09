package maintenance

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

type unavailableBlobs struct{ blobstore.BlobStore }

func (unavailableBlobs) Delete(string) error { return errors.New("temporarily offline") }

func TestBurnCleanupRetriesAfterBlobFailure(t *testing.T) {
	ctx := context.Background()
	st := store.NewMemoryStore()
	blobs, err := blobstore.NewFileBlobStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer blobs.Close()
	if err := st.CreateUser(ctx, &model.User{ID: "owner", Username: "owner"}); err != nil {
		t.Fatal(err)
	}
	if err := blobs.Put("owned", strings.NewReader("opaque")); err != nil {
		t.Fatal(err)
	}
	if err := st.SaveMedia(ctx, &model.Media{ID: "owned", OwnerID: "owner", Size: 6, CreatedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}
	if err := st.DeleteUser(ctx, "owner"); err != nil {
		t.Fatal(err)
	}
	if err := DrainBlobs(ctx, st, unavailableBlobs{blobs}); err == nil {
		t.Fatal("failure ignored")
	}
	pending, err := st.PendingBlobDeletes(ctx)
	if err != nil || len(pending) != 1 {
		t.Fatalf("deletion lost: %v %v", pending, err)
	}
	if err := DrainBlobs(ctx, st, blobs); err != nil {
		t.Fatal(err)
	}
	pending, err = st.PendingBlobDeletes(ctx)
	if err != nil || len(pending) != 0 {
		t.Fatalf("queue not drained: %v %v", pending, err)
	}
	if file, err := blobs.Get("owned"); !errors.Is(err, store.ErrNotFound) {
		if file != nil {
			file.Close()
		}
		t.Fatalf("burned file remains: %v", err)
	}
}
