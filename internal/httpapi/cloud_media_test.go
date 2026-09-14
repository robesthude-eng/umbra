package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"umbra/server/internal/cloudcrypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

func TestCloudMedia(t *testing.T) {
	f := newMediaFixture(t, 1<<20)
	keys, err := cloudcrypto.Generate(nil)
	if err != nil {
		t.Fatal(err)
	}
	f.handler = NewServerWithBlobStore(f.cfg, f.st, ws.NewHub(), f.blobs, keys).Handler
	for _, plain := range [][]byte{nil, []byte("voice bytes"), bytes.Repeat([]byte{0x9e}, 1<<20)} {
		body, contentType := multipartBody(t, mediaPart{name: "file", value: plain, file: true}, mediaPart{name: "content_type", value: []byte("audio/mp4")})
		w := mediaRequest(f.handler, "POST", "/v1/media", f.owner, contentType, body, false)
		if w.Code != 201 {
			t.Fatal(w.Code, w.Body.String())
		}
		var response mediaResponse
		if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
			t.Fatal(err)
		}
		m, err := f.st.GetMedia(context.Background(), response.ID)
		if err != nil || m.StorageFormat != 1 || m.Size != int64(len(plain)) {
			t.Fatal("metadata", m, err)
		}
		raw, err := os.ReadFile(filepath.Join(f.dir, m.ObjectID()))
		if err != nil || bytes.Equal(raw, plain) || (len(plain) > 0 && bytes.Contains(raw, plain)) {
			t.Fatal("plaintext stored", err)
		}
		w = mediaRequest(f.handler, "GET", "/v1/media/"+m.ID, f.owner, "", nil, false)
		if w.Code != 200 || !bytes.Equal(w.Body.Bytes(), plain) || w.Header().Get("Content-Type") != "audio/mp4" {
			t.Fatal("download", w.Code)
		}
		if w := mediaRequest(f.handler, "GET", "/v1/media/"+m.ID, f.other, "", nil, false); w.Code != 404 {
			t.Fatal("unauthorized download", w.Code)
		}
		withoutKey := NewServerWithBlobStore(f.cfg, f.st, ws.NewHub(), f.blobs).Handler
		if w := mediaRequest(withoutKey, "GET", "/v1/media/"+m.ID, f.owner, "", nil, false); w.Code != 500 {
			t.Fatal("missing key did not fail closed", w.Code)
		}
	}
	// Old public URLs still resolve when logical IDs and physical objects differ.
	if err := f.blobs.Put("old-object", bytes.NewBufferString("old")); err != nil {
		t.Fatal(err)
	}
	if err := f.st.SaveMedia(context.Background(), &model.Media{ID: "old-id", BlobID: "old-object", OwnerID: "owner", Size: 3, ContentType: "image/png", CreatedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}
	w := mediaRequest(f.handler, "GET", "/v1/media/old-id", f.owner, "", nil, false)
	if w.Code != 200 || w.Body.String() != "old" {
		t.Fatal("legacy download", w.Code)
	}
}

func TestDownloadWithholdsLastByteOnInvalidStream(t *testing.T) {
	for _, src := range []io.Reader{bytes.NewBufferString("12345extra"), io.MultiReader(bytes.NewBufferString("12345"), brokenMediaReader{})} {
		var out bytes.Buffer
		if err := copyVerifiedMedia(&out, src, 5); err == nil {
			t.Fatal("invalid completion accepted")
		}
		if out.Len() >= 5 {
			t.Fatal("client received full Content-Length before verification")
		}
	}
}

type brokenMediaReader struct{}

func (brokenMediaReader) Read([]byte) (int, error) { return 0, io.ErrUnexpectedEOF }

type ambiguousMediaStore struct{ store.Store }

func (s ambiguousMediaStore) SaveMedia(ctx context.Context, m *model.Media) error {
	if err := s.Store.SaveMedia(ctx, m); err != nil {
		return err
	}
	return errors.New("commit reply lost")
}

func TestCloudUploadRetainsBlobAfterLostCommitReply(t *testing.T) {
	f := newMediaFixture(t, 1024)
	keys, err := cloudcrypto.Generate(nil)
	if err != nil {
		t.Fatal(err)
	}
	f.handler = NewServerWithBlobStore(f.cfg, ambiguousMediaStore{f.st}, ws.NewHub(), f.blobs, keys).Handler
	body, ct := multipartBody(t, mediaPart{name: "file", value: []byte("persisted"), file: true})
	w := mediaRequest(f.handler, "POST", "/v1/media", f.owner, ct, body, false)
	if w.Code != 500 {
		t.Fatal(w.Code)
	}
	ids, err := f.blobs.List()
	if err != nil || len(ids) != 1 {
		t.Fatal("committed blob deleted", ids, err)
	}
	if _, err := f.st.GetMedia(context.Background(), ids[0]); err != nil {
		t.Fatal(err)
	}
}
