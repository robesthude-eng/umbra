package cloudstorage

import (
	"bytes"
	"context"
	"errors"
	"io"
	"testing"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/cloudcrypto"
	"umbra/server/internal/model"
)

type migrationFixture struct {
	media     model.Media
	loseReply bool
	commits   int
}

func (s *migrationFixture) LockBlobs(context.Context, bool) (func(), error)  { return func() {}, nil }
func (s *migrationFixture) MigrateMessageBatch(context.Context) (int, error) { return 0, nil }
func (s *migrationFixture) LegacyMedia(context.Context) ([]*model.Media, error) {
	if s.media.StorageFormat != 0 {
		return nil, nil
	}
	m := s.media
	return []*model.Media{&m}, nil
}
func (s *migrationFixture) CommitEncryptedMedia(_ context.Context, id, old, next string) error {
	if id != s.media.ID || old != s.media.ObjectID() {
		return errors.New("wrong pointer")
	}
	s.media.BlobID, s.media.StorageFormat = next, 1
	s.commits++
	if s.loseReply {
		return errors.New("commit reply lost")
	}
	return nil
}

func TestMigrationResumesAfterAmbiguousCommit(t *testing.T) {
	ctx := context.Background()
	b, err := blobstore.NewFileBlobStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	plain := bytes.Repeat([]byte("old family photo"), 10000)
	if err := b.Put("legacy", bytes.NewReader(plain)); err != nil {
		t.Fatal(err)
	}
	keys, err := cloudcrypto.Generate(nil)
	if err != nil {
		t.Fatal(err)
	}
	s := &migrationFixture{media: model.Media{ID: "public", BlobID: "legacy", Size: int64(len(plain))}, loseReply: true}
	if err := Migrate(ctx, s, b, keys); err == nil {
		t.Fatal("lost reply not surfaced")
	}
	newID := s.media.ObjectID()
	if newID == "legacy" || s.commits != 1 {
		t.Fatal("pointer not switched")
	}
	if err := Migrate(ctx, s, b, keys); err != nil {
		t.Fatal("retry", err)
	}
	if s.commits != 1 || s.media.ObjectID() != newID {
		t.Fatal("retry duplicated migration")
	}
	r, err := b.Get(newID)
	if err != nil {
		t.Fatal("committed copy lost", err)
	}
	defer r.Close()
	dec, err := keys.DecryptReader(r, "public")
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(dec)
	if err != nil || !bytes.Equal(got, plain) {
		t.Fatal("content changed", err)
	}
	old, err := b.Get("legacy")
	if err != nil {
		t.Fatal("original removed before durable cleanup", err)
	}
	old.Close()
}

func TestMigrationNeverPublishesWrongSize(t *testing.T) {
	b, err := blobstore.NewFileBlobStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	if err := b.Put("legacy", bytes.NewBufferString("short")); err != nil {
		t.Fatal(err)
	}
	k, err := cloudcrypto.Generate(nil)
	if err != nil {
		t.Fatal(err)
	}
	s := &migrationFixture{media: model.Media{ID: "legacy", Size: 100}}
	if err := Migrate(context.Background(), s, b, k); err == nil {
		t.Fatal("size mismatch accepted")
	}
	if s.commits != 0 || s.media.ObjectID() != "legacy" {
		t.Fatal("invalid copy published")
	}
}
