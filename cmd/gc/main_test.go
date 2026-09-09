package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

type metadataFailure struct{ store.Store }

func (metadataFailure) GetMedia(context.Context, string) (*model.Media, error) {
	return nil, errors.New("database offline")
}

func TestGCDryRunAndFailClosed(t *testing.T) {
	ctx := context.Background()
	st := store.NewMemoryStore()
	dir := t.TempDir()
	blobs, err := blobstore.NewFileBlobStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer blobs.Close()
	if err := st.CreateUser(ctx, &model.User{ID: "owner", Username: "owner"}); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"live", "orphan"} {
		if err := blobs.Put(id, strings.NewReader("opaque")); err != nil {
			t.Fatal(err)
		}
		old := time.Now().Add(-48 * time.Hour)
		if err := os.Chtimes(filepath.Join(dir, id), old, old); err != nil {
			t.Fatal(err)
		}
	}
	if err := st.SaveMedia(ctx, &model.Media{ID: "live", OwnerID: "owner", Size: 6}); err != nil {
		t.Fatal(err)
	}
	if err := collect(ctx, st, blobs, false); err != nil {
		t.Fatal(err)
	}
	ids, err := blobs.List()
	if err != nil || len(ids) != 2 {
		t.Fatalf("dry-run deleted files: %v %v", ids, err)
	}
	if err := collect(ctx, metadataFailure{st}, blobs, true); err == nil {
		t.Fatal("metadata error ignored")
	}
	ids, err = blobs.List()
	if err != nil || len(ids) != 2 {
		t.Fatalf("database error caused deletion: %v %v", ids, err)
	}
	if err := collect(ctx, st, blobs, true); err != nil {
		t.Fatal(err)
	}
	ids, err = blobs.List()
	if err != nil || len(ids) != 1 || ids[0] != "live" {
		t.Fatalf("GC removed live file: %v %v", ids, err)
	}
	if err := blobs.Put("recent", strings.NewReader("upload")); err != nil {
		t.Fatal(err)
	}
	if err := collect(ctx, st, blobs, true); err != nil {
		t.Fatal(err)
	}
	ids, err = blobs.List()
	if err != nil || len(ids) != 2 {
		t.Fatalf("recent upload removed: %v %v", ids, err)
	}
}
