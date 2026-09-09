package blobstore

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"umbra/server/internal/store"
)

// S3BlobStore хранит блобы в S3-совместимом объектном хранилище
// (AWS S3, MinIO, Backblaze B2, DigitalOcean Spaces и т.п.).
// Блобы — непрозрачный ciphertext; ключи и расшифровка остаются у клиента.
type S3BlobStore struct {
	client *minio.Client
	bucket string
}

var _ BlobStore = (*S3BlobStore)(nil)
var _ Lister = (*S3BlobStore)(nil)

// NewS3BlobStore создаёт клиент и гарантирует наличие бакета (создаёт при отсутствии).
func NewS3BlobStore(endpoint, accessKey, secretKey, bucket, region string, useSSL bool) (*S3BlobStore, error) {
	if endpoint == "" || bucket == "" {
		return nil, errors.New("blobstore: endpoint and bucket are required")
	}
	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: useSSL,
		Region: region,
	})
	if err != nil {
		return nil, fmt.Errorf("s3 client: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	exists, err := client.BucketExists(ctx, bucket)
	if err != nil {
		return nil, fmt.Errorf("s3 bucket check: %w", err)
	}
	if !exists {
		if err := client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{Region: region}); err != nil {
			return nil, fmt.Errorf("s3 make bucket: %w", err)
		}
	}
	return &S3BlobStore{client: client, bucket: bucket}, nil
}

func (s *S3BlobStore) Put(id string, r io.Reader) error {
	return s.PutContext(context.Background(), id, r)
}

func (s *S3BlobStore) PutContext(parent context.Context, id string, r io.Reader) error {
	ctx, cancel := context.WithTimeout(parent, 5*time.Minute)
	defer cancel()
	if !validID(id) {
		return ErrInvalidID
	}
	if _, err := s.client.PutObject(ctx, s.bucket, id, r, -1, minio.PutObjectOptions{}); err != nil {
		return fmt.Errorf("s3 put: %w", err)
	}
	return nil
}

func (s *S3BlobStore) Get(id string) (io.ReadCloser, error) {
	return s.GetContext(context.Background(), id)
}

func (s *S3BlobStore) GetContext(parent context.Context, id string) (io.ReadCloser, error) {
	if !validID(id) {
		return nil, ErrInvalidID
	}
	ctx, cancel := context.WithTimeout(parent, 5*time.Minute)
	// Context остаётся жив до закрытия потока.
	if _, err := s.client.StatObject(ctx, s.bucket, id, minio.StatObjectOptions{}); err != nil {
		cancel()
		if isNoSuchKey(err) {
			return nil, store.ErrNotFound
		}
		return nil, fmt.Errorf("s3 stat: %w", err)
	}
	obj, err := s.client.GetObject(ctx, s.bucket, id, minio.GetObjectOptions{})
	if err != nil {
		cancel()
		return nil, fmt.Errorf("s3 get: %w", err)
	}
	return &contextReader{ReadCloser: obj, cancel: cancel}, nil
}

type contextReader struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (r *contextReader) Close() error { r.cancel(); return r.ReadCloser.Close() }

func (s *S3BlobStore) Delete(id string) error {
	return s.DeleteContext(context.Background(), id)
}

func (s *S3BlobStore) DeleteContext(parent context.Context, id string) error {
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	if !validID(id) {
		return ErrInvalidID
	}
	if err := s.client.RemoveObject(ctx, s.bucket, id, minio.RemoveObjectOptions{}); err != nil {
		return fmt.Errorf("s3 remove: %w", err)
	}
	return nil
}

// List перечисляет id всех объектов в бакете (для GC).
func (s *S3BlobStore) List() ([]string, error) {
	return s.ListContext(context.Background())
}

func (s *S3BlobStore) ListContext(parent context.Context) ([]string, error) {
	ctx, cancel := context.WithTimeout(parent, 5*time.Minute)
	defer cancel()
	out := make([]string, 0)
	for obj := range s.client.ListObjects(ctx, s.bucket, minio.ListObjectsOptions{}) {
		if obj.Err != nil {
			return nil, fmt.Errorf("s3 list: %w", obj.Err)
		}
		out = append(out, obj.Key)
	}
	return out, ctx.Err()
}

func (s *S3BlobStore) Close() error { return nil }

func (s *S3BlobStore) ModifiedAt(parent context.Context, id string) (time.Time, error) {
	if !validID(id) {
		return time.Time{}, ErrInvalidID
	}
	ctx, cancel := context.WithTimeout(parent, 30*time.Second)
	defer cancel()
	info, err := s.client.StatObject(ctx, s.bucket, id, minio.StatObjectOptions{})
	if err != nil && isNoSuchKey(err) {
		return time.Time{}, store.ErrNotFound
	}
	return info.LastModified, err
}

func isNoSuchKey(err error) bool {
	resp := minio.ToErrorResponse(err)
	return resp.Code == "NoSuchKey" || resp.StatusCode == 404
}
