package blobstore

import (
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"umbra/server/internal/store"
)

func TestFileBlobStoreRoundTrip(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "nested", "blobs")
	f, err := NewFileBlobStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = f.Close() })
	want := []byte{0, 255, 1, '\r', '\n', '=', 128}
	if err := f.Put("Abc_012-xyz", bytes.NewReader(want)); err != nil {
		t.Fatal(err)
	}
	if got := readBlob(t, f, "Abc_012-xyz"); !bytes.Equal(got, want) {
		t.Fatalf("bytes changed: %x != %x", got, want)
	}
	info, err := os.Stat(filepath.Join(dir, "Abc_012-xyz"))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm()&0077 != 0 {
		t.Fatalf("blob permissions: %v", info.Mode())
	}
	if err := f.Delete("Abc_012-xyz"); err != nil {
		t.Fatal(err)
	}
	if _, err := f.Get("Abc_012-xyz"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("Get deleted: %v", err)
	}
	if err := f.Delete("missing"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("Delete missing: %v", err)
	}
}

func TestFileBlobStoreInvalidIDs(t *testing.T) {
	f, err := NewFileBlobStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{"", ".", "..", "../escape", "/absolute", `a\b`, "a/b", "a.b", "a b", "x\x00y", "файл", strings.Repeat("a", 256)} {
		t.Run(id, func(t *testing.T) {
			if err := f.Put(id, strings.NewReader("data")); !errors.Is(err, ErrInvalidID) {
				t.Fatalf("Put: %v", err)
			}
			if _, err := f.Get(id); !errors.Is(err, ErrInvalidID) {
				t.Fatalf("Get: %v", err)
			}
			if err := f.Delete(id); !errors.Is(err, ErrInvalidID) {
				t.Fatalf("Delete: %v", err)
			}
		})
	}
}

type failingReader struct{ err error }

func (r failingReader) Read([]byte) (int, error) { return 0, r.err }

func TestFileBlobStoreFailedPutAndConflict(t *testing.T) {
	dir := t.TempDir()
	f, err := NewFileBlobStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	failure := errors.New("interrupted upload")
	err = f.Put("broken", io.MultiReader(strings.NewReader("partial"), failingReader{failure}))
	if !errors.Is(err, failure) {
		t.Fatalf("Put: %v", err)
	}
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("partial files left behind: %v, %v", entries, err)
	}
	if err := f.Put("existing", strings.NewReader("original")); err != nil {
		t.Fatal(err)
	}
	if err := f.Put("existing", strings.NewReader("replacement")); !errors.Is(err, store.ErrConflict) {
		t.Fatalf("duplicate Put: %v", err)
	}
	if got := string(readBlob(t, f, "existing")); got != "original" {
		t.Fatalf("existing blob overwritten: %q", got)
	}
}

func TestFileBlobStoreConcurrentPut(t *testing.T) {
	f, err := NewFileBlobStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	const count = 8
	results := make(chan error, count)
	var wg sync.WaitGroup
	for i := 0; i < count; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results <- f.Put("same-id", bytes.NewReader(bytes.Repeat([]byte{0x9a}, 8192)))
		}()
	}
	wg.Wait()
	close(results)
	successes := 0
	for err := range results {
		if err == nil {
			successes++
		} else if !errors.Is(err, store.ErrConflict) {
			t.Fatal(err)
		}
	}
	if successes != 1 || len(readBlob(t, f, "same-id")) != 8192 {
		t.Fatalf("atomic publication failed, successes=%d", successes)
	}
}

func TestFileBlobStoreRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	out := filepath.Join(t.TempDir(), "outside")
	if err := os.WriteFile(out, []byte("outside"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(out, filepath.Join(dir, "linked")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	f, err := NewFileBlobStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.Get("linked"); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("symlink was readable: %v", err)
	}
}

func readBlob(t *testing.T, f BlobStore, id string) []byte {
	t.Helper()
	r, err := f.Get(id)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	b, err := io.ReadAll(r)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
