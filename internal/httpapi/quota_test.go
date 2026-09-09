package httpapi

import (
	"bytes"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

// uploadMedia отправляет multipart-запрос загрузки медиа.
func uploadMedia(t *testing.T, h http.Handler, token string, data []byte) (int, map[string]any) {
	t.Helper()
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	part, _ := w.CreateFormFile("file", "blob.bin")
	part.Write(data)
	w.Close()
	req := httptest.NewRequest(http.MethodPost, "/v1/media", &buf)
	req.Header.Set("Content-Type", w.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	m := map[string]any{}
	_ = json.NewDecoder(rec.Body).Decode(&m)
	return rec.Code, m
}

func TestMediaQuota(t *testing.T) {
	cfg := &config.Config{
		AllowLegacyAuth:   true,
		ListenAddr:        ":0",
		Store:             "memory",
		TokenTTL:          time.Hour,
		MaxMessageBytes:   1 << 20,
		MaxMediaBytes:     1 << 20,
		MaxUserMediaBytes: 100, // маленькая квота: 100 байт на пользователя
	}
	st := store.NewMemoryStore()
	hub := ws.NewHub()
	blobs, err := blobstore.NewFileBlobStore(t.TempDir())
	if err != nil {
		t.Fatalf("blobstore: %v", err)
	}
	h := NewServerWithBlobStore(cfg, st, hub, blobs).Handler

	alice := newKeyBundle("alice")
	if code, _ := register(t, h, alice); code != http.StatusCreated {
		t.Fatalf("register: %d", code)
	}
	tok := authenticate(t, h, "alice", alice.privEd)

	// 80 байт — в пределах квоты
	code, _ := uploadMedia(t, h, tok, bytes.Repeat([]byte{0xaa}, 80))
	if code != http.StatusCreated {
		t.Fatalf("первая загрузка должна пройти (80 байт), получен %d", code)
	}

	// ещё 80 байт — суммарно 160 > квоты 100, должен быть 413
	code, m := uploadMedia(t, h, tok, bytes.Repeat([]byte{0xbb}, 80))
	if code != http.StatusRequestEntityTooLarge {
		t.Fatalf("ожидался 413 (превышение квоты), получен %d (%v)", code, m)
	}
}

func TestMediaNoQuota(t *testing.T) {
	cfg := &config.Config{
		AllowLegacyAuth: true,
		ListenAddr:      ":0",
		Store:           "memory",
		TokenTTL:        time.Hour,
		MaxMessageBytes: 1 << 20,
		MaxMediaBytes:   1 << 20,
		// MaxUserMediaBytes = 0 → без квоты
	}
	st := store.NewMemoryStore()
	hub := ws.NewHub()
	blobs, _ := blobstore.NewFileBlobStore(t.TempDir())
	h := NewServerWithBlobStore(cfg, st, hub, blobs).Handler

	alice := newKeyBundle("alice")
	register(t, h, alice)
	tok := authenticate(t, h, "alice", alice.privEd)

	code, _ := uploadMedia(t, h, tok, bytes.Repeat([]byte{0xcc}, 500))
	if code != http.StatusCreated {
		t.Fatalf("без квоты загрузка должна пройти, получен %d", code)
	}
}
