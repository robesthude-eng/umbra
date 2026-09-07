package gc

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

// putBlob кладёт байты в файловый blobstore и возвращает путь к файлу.
func putBlob(t *testing.T, blobs *blobstore.FileBlobStore, dir, id string, data []byte) string {
	t.Helper()
	if err := blobs.Put(id, bytes.NewReader(data)); err != nil {
		t.Fatalf("Put(%s): %v", id, err)
	}
	return filepath.Join(dir, id)
}

// ageFile устанавливает mtime файла в now минус d.
func ageFile(t *testing.T, path string, now time.Time, d time.Duration) {
	t.Helper()
	mtime := now.Add(-d)
	if err := os.Chtimes(path, mtime, mtime); err != nil {
		t.Fatalf("Chtimes(%s): %v", path, err)
	}
}

func TestRemoveOrphansGracePeriod(t *testing.T) {
	dir := t.TempDir()
	blobs, err := blobstore.NewFileBlobStore(dir)
	if err != nil {
		t.Fatalf("NewFileBlobStore: %v", err)
	}
	defer blobs.Close()

	st := store.NewMemoryStore()
	defer st.Close()
	ctx := context.Background()
	now := time.Now()

	// Memory-store проверяет существование владельца медиа (как FK в PostgreSQL).
	if err := st.CreateUser(ctx, &model.User{ID: "user-1", Username: "owner-1", CreatedAt: now}); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	// 1. Блоб с метаданными — не сирота, всегда остаётся.
	putBlob(t, blobs, dir, "referenced0000000000000000000000000", []byte("cipher-1"))
	if err := st.SaveMedia(ctx, &model.Media{
		ID:          "referenced0000000000000000000000000",
		OwnerID:     "user-1",
		ContentType: "application/octet-stream",
		Size:        8,
		CreatedAt:   now,
	}); err != nil {
		t.Fatalf("SaveMedia: %v", err)
	}

	// 2. Старый сирота (48 часов) — должен быть удалён.
	oldOrphan := putBlob(t, blobs, dir, "oldorphan00000000000000000000000000", []byte("cipher-2"))
	ageFile(t, oldOrphan, now, 48*time.Hour)

	// 3. Свежий сирота (1 час) — льготный период, пропускается.
	freshOrphan := putBlob(t, blobs, dir, "freshorphan000000000000000000000000", []byte("cipher-3"))
	ageFile(t, freshOrphan, now, time.Hour)

	stats, err := RemoveOrphans(ctx, st, blobs, DefaultMinAge, now)
	if err != nil {
		t.Fatalf("RemoveOrphans: %v", err)
	}
	if stats.Listed != 3 {
		t.Errorf("Listed = %d, want 3", stats.Listed)
	}
	if stats.Removed != 1 {
		t.Errorf("Removed = %d, want 1", stats.Removed)
	}
	if stats.Skipped != 1 {
		t.Errorf("Skipped = %d, want 1", stats.Skipped)
	}
	if stats.Errors != 0 {
		t.Errorf("Errors = %d, want 0", stats.Errors)
	}

	// Старый сирота удалён, остальные на месте.
	if _, err := blobs.Get("oldorphan00000000000000000000000000"); err != store.ErrNotFound {
		t.Errorf("старый сирота не удалён: Get err = %v", err)
	}
	if _, err := blobs.Get("referenced0000000000000000000000000"); err != nil {
		t.Errorf("блоб с метаданными удалён: Get err = %v", err)
	}
	if _, err := blobs.Get("freshorphan000000000000000000000000"); err != nil {
		t.Errorf("свежий сирота удалён вопреки льготному периоду: Get err = %v", err)
	}

	// Повторный запуск через двое суток: свежий сирота постарел и удаляется.
	later := now.Add(48 * time.Hour)
	stats2, err := RemoveOrphans(ctx, st, blobs, DefaultMinAge, later)
	if err != nil {
		t.Fatalf("RemoveOrphans (second): %v", err)
	}
	if stats2.Listed != 2 || stats2.Removed != 1 || stats2.Skipped != 0 {
		t.Errorf("второй проход: %+v, want Listed=2 Removed=1 Skipped=0", stats2)
	}
	if _, err := blobs.Get("freshorphan000000000000000000000000"); err != store.ErrNotFound {
		t.Errorf("постаревший сирота не удалён: Get err = %v", err)
	}
}

func TestRemoveOrphansZeroMinAgeDeletesFresh(t *testing.T) {
	dir := t.TempDir()
	blobs, err := blobstore.NewFileBlobStore(dir)
	if err != nil {
		t.Fatalf("NewFileBlobStore: %v", err)
	}
	defer blobs.Close()

	st := store.NewMemoryStore()
	defer st.Close()
	ctx := context.Background()
	now := time.Now()

	putBlob(t, blobs, dir, "fresh000000000000000000000000000000", []byte("cipher"))

	// minAge = 0 — льготный период отключён, свежий сирота удаляется сразу.
	stats, err := RemoveOrphans(ctx, st, blobs, 0, now)
	if err != nil {
		t.Fatalf("RemoveOrphans: %v", err)
	}
	if stats.Removed != 1 || stats.Skipped != 0 {
		t.Errorf("stats = %+v, want Removed=1 Skipped=0", stats)
	}
}

func TestRemoveOrphansUnknownModTimeTreatedAsOld(t *testing.T) {
	// fakeLister возвращает блоб с нулевым ModTime: возраст неизвестен,
	// GC трактует его как достаточно старый и удаляет.
	dir := t.TempDir()
	blobs, err := blobstore.NewFileBlobStore(dir)
	if err != nil {
		t.Fatalf("NewFileBlobStore: %v", err)
	}
	defer blobs.Close()

	id := "unknown0000000000000000000000000000"
	putBlob(t, blobs, dir, id, []byte("cipher"))

	st := store.NewMemoryStore()
	defer st.Close()

	fake := &zeroModTimeStore{inner: blobs, id: id}
	stats, err := RemoveOrphans(context.Background(), st, fake, DefaultMinAge, time.Now())
	if err != nil {
		t.Fatalf("RemoveOrphans: %v", err)
	}
	if stats.Removed != 1 || stats.Skipped != 0 {
		t.Errorf("stats = %+v, want Removed=1 Skipped=0", stats)
	}
	if _, err := blobs.Get(id); err != store.ErrNotFound {
		t.Errorf("блоб с неизвестным возрастом не удалён: %v", err)
	}
}

// zeroModTimeStore оборачивает FileBlobStore, подменяя ModTime на нулевое.
type zeroModTimeStore struct {
	inner *blobstore.FileBlobStore
	id    string
}

func (z *zeroModTimeStore) Put(id string, r io.Reader) error { return z.inner.Put(id, r) }
func (z *zeroModTimeStore) Get(id string) (io.ReadCloser, error) {
	return z.inner.Get(id)
}
func (z *zeroModTimeStore) Delete(id string) error { return z.inner.Delete(id) }
func (z *zeroModTimeStore) Close() error           { return nil }
func (z *zeroModTimeStore) List() ([]blobstore.BlobInfo, error) {
	return []blobstore.BlobInfo{{ID: z.id}}, nil
}

var (
	_ blobstore.BlobStore = (*zeroModTimeStore)(nil)
	_ blobstore.Lister    = (*zeroModTimeStore)(nil)
)
