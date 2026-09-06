package blobstore

import (
	"context"
	"errors"
	"fmt"
	"io"

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
	ctx := context.Background()
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
	if !validID(id) {
		return ErrInvalidID
	}
	if _, err := s.client.PutObject(context.Background(), s.bucket, id, r, -1, minio.PutObjectOptions{}); err != nil {
		return fmt.Errorf("s3 put: %w", err)
	}
	return nil
}

func (s *S3BlobStore) Get(id string) (io.ReadCloser, error) {
	if !validID(id) {
		return nil, ErrInvalidID
	}
	// StatObject проверяет существование сразу (GetObject откладывает ошибку до чтения).
	if _, err := s.client.StatObject(context.Background(), s.bucket, id, minio.StatObjectOptions{}); err != nil {
		if isNoSuchKey(err) {
			return nil, store.ErrNotFound
		}
		return nil, fmt.Errorf("s3 stat: %w", err)
	}
	obj, err := s.client.GetObject(context.Background(), s.bucket, id, minio.GetObjectOptions{})
	if err != nil {
		return nil, fmt.Errorf("s3 get: %w", err)
	}
	return obj, nil
}

func (s *S3BlobStore) Delete(id string) error {
	if !validID(id) {
		return ErrInvalidID
	}
	if err := s.client.RemoveObject(context.Background(), s.bucket, id, minio.RemoveObjectOptions{}); err != nil {
		return fmt.Errorf("s3 remove: %w", err)
	}
	return nil
}

// List перечисляет id всех объектов в бакете (для GC).
func (s *S3BlobStore) List() ([]string, error) {
	out := make([]string, 0)
	for obj := range s.client.ListObjects(context.Background(), s.bucket, minio.ListObjectsOptions{}) {
		if obj.Err != nil {
			return nil, fmt.Errorf("s3 list: %w", obj.Err)
		}
		out = append(out, obj.Key)
	}
	return out, nil
}

func (s *S3BlobStore) Close() error { return nil }

func isNoSuchKey(err error) bool {
	resp := minio.ToErrorResponse(err)
	return resp.Code == "NoSuchKey" || resp.StatusCode == 404
}
