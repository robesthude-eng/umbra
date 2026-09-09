// Package blobstore хранит непрозрачные байты: ключи и расшифровка остаются у клиента.
package blobstore

import (
	"context"
	"errors"
	"io"
	"time"
)

var ErrInvalidID = errors.New("blobstore: invalid id")

// BlobStore сохраняет блобы целиком; неудачный Put не публикует частичный файл.
type BlobStore interface {
	Put(id string, r io.Reader) error
	Get(id string) (io.ReadCloser, error)
	Delete(id string) error
	Close() error
}

// Lister — опциональная возможность перечислить id всех блобов (для GC).
// Реализуется FileBlobStore и S3BlobStore; GC делает type-assert.
type Lister interface {
	List() ([]string, error)
}

type TimestampReader interface {
	ModifiedAt(ctx context.Context, id string) (time.Time, error)
}

// Context variants allow HTTP cancellation and bounded maintenance sweeps.
func Put(ctx context.Context, blobs BlobStore, id string, r io.Reader) error {
	if b, ok := blobs.(interface {
		PutContext(context.Context, string, io.Reader) error
	}); ok {
		return b.PutContext(ctx, id, r)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return blobs.Put(id, r)
}

func Get(ctx context.Context, blobs BlobStore, id string) (io.ReadCloser, error) {
	if b, ok := blobs.(interface {
		GetContext(context.Context, string) (io.ReadCloser, error)
	}); ok {
		return b.GetContext(ctx, id)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return blobs.Get(id)
}

func Delete(ctx context.Context, blobs BlobStore, id string) error {
	if b, ok := blobs.(interface {
		DeleteContext(context.Context, string) error
	}); ok {
		return b.DeleteContext(ctx, id)
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	return blobs.Delete(id)
}

func List(ctx context.Context, blobs BlobStore) ([]string, error) {
	if b, ok := blobs.(interface {
		ListContext(context.Context) ([]string, error)
	}); ok {
		return b.ListContext(ctx)
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if b, ok := blobs.(Lister); ok {
		return b.List()
	}
	return nil, errors.New("blobstore cannot list objects")
}

// validID запрещает разделители пути, точки и слишком длинные имена файлов.
func validID(id string) bool {
	if len(id) == 0 || len(id) > 255 {
		return false
	}
	for _, c := range id {
		if !(c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}
