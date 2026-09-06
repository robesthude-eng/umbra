package blobstore

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"umbra/server/internal/store"
)

// FileBlobStore хранит блобы в закрытой для других пользователей ОС директории.
type FileBlobStore struct {
	dir string
}

var _ BlobStore = (*FileBlobStore)(nil)
var _ Lister = (*FileBlobStore)(nil)

func NewFileBlobStore(dir string) (*FileBlobStore, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(abs, 0700); err != nil {
		return nil, fmt.Errorf("create blob directory: %w", err)
	}
	return &FileBlobStore{dir: abs}, nil
}

func (f *FileBlobStore) Put(id string, r io.Reader) error {
	if !validID(id) {
		return ErrInvalidID
	}
	// Временный файл недоступен по API; читатель увидит только завершённый blob.
	tmp, err := os.CreateTemp(f.dir, ".upload-")
	if err != nil {
		return fmt.Errorf("create blob: %w", err)
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()
	if _, err := io.Copy(tmp, r); err != nil {
		return fmt.Errorf("write blob: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("sync blob: %w", err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close blob: %w", err)
	}
	// Link публикует файл атомарно и не перезаписывает существующий id.
	if err := os.Link(tmp.Name(), filepath.Join(f.dir, id)); err != nil {
		if errors.Is(err, os.ErrExist) {
			return store.ErrConflict
		}
		return fmt.Errorf("publish blob: %w", err)
	}
	return nil
}

func (f *FileBlobStore) Get(id string) (io.ReadCloser, error) {
	if !validID(id) {
		return nil, ErrInvalidID
	}
	path := filepath.Join(f.dir, id)
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, store.ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("stat blob: %w", err)
	}
	// В хранилище допустимы только обычные файлы, без симлинков и устройств.
	if !info.Mode().IsRegular() {
		return nil, store.ErrNotFound
	}
	r, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, store.ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("open blob: %w", err)
	}
	return r, nil
}

func (f *FileBlobStore) Delete(id string) error {
	if !validID(id) {
		return ErrInvalidID
	}
	if err := os.Remove(filepath.Join(f.dir, id)); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return store.ErrNotFound
		}
		return fmt.Errorf("delete blob: %w", err)
	}
	return nil
}

// List перечисляет id всех блобов в директории (только обычные файлы,
// временные ".upload-*" пропускаются).
func (f *FileBlobStore) List() ([]string, error) {
	entries, err := os.ReadDir(f.dir)
	if err != nil {
		return nil, fmt.Errorf("list blobs: %w", err)
	}
	out := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		name := e.Name()
		if len(name) > 0 && name[0] == '.' {
			continue // .upload-* временные файлы
		}
		out = append(out, name)
	}
	return out, nil
}

func (f *FileBlobStore) Close() error { return nil }
