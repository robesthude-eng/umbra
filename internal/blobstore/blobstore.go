// Package blobstore хранит непрозрачные байты: ключи и расшифровка остаются у клиента.
package blobstore

import (
	"errors"
	"io"
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
