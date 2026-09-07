package blobstore

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"os"
	"testing"
	"time"
)

func TestS3RoundTrip(t *testing.T) {
	endpoint := os.Getenv("TEST_S3_ENDPOINT")
	if endpoint == "" { t.Skip("TEST_S3_ENDPOINT not set") }
	blobs, err := NewS3BlobStore(endpoint, os.Getenv("TEST_S3_ACCESS_KEY"), os.Getenv("TEST_S3_SECRET_KEY"),
		os.Getenv("TEST_S3_BUCKET"), "us-east-1", os.Getenv("TEST_S3_USE_SSL") == "true")
	if err != nil { t.Fatal(err) }
	defer blobs.Close()
	id := fmt.Sprintf("umbra-test-%d", time.Now().UnixNano())
	defer blobs.Delete(id)
	want := []byte{0, 255, 128, 42, 13, 10}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := Put(ctx, blobs, id, bytes.NewReader(want)); err != nil { t.Fatal(err) }
	r, err := Get(ctx, blobs, id)
	if err != nil { t.Fatal(err) }
	got, err := io.ReadAll(r)
	r.Close()
	if err != nil || !bytes.Equal(got, want) { t.Fatalf("round trip: %x %v", got, err) }
	canceled, stop := context.WithCancel(ctx)
	stop()
	if err := Delete(canceled, blobs, id); err == nil { t.Fatal("cancellation ignored") }
}
