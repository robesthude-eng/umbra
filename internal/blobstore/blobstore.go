// Package blobstore хранит непрозрачные байты: ключи и расшифровка остаются у клиента.
package blobstore

import (
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

// BlobInfo — id блоба и время его последней модификации.
// ModTime нужна GC для льготного периода: свежий блоб может быть частью
// незавершённой загрузки (blob публикуется раньше, чем фиксируются метаданные),
// поэтому удалять его нельзя. Нулевое ModTime означает «время неизвестно».
type BlobInfo struct {
	ID      string
	ModTime time.Time
}

// Lister — опциональная возможность перечислить все блобы (для GC).
// Реализуется FileBlobStore и S3BlobStore; GC делает type-assert.
type Lister interface {
	List() ([]BlobInfo, error)
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
