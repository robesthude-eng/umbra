package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

type mediaFixture struct {
	cfg     *config.Config
	st      *store.MemoryStore
	blobs   *blobstore.FileBlobStore
	dir     string
	handler http.Handler
	owner   string
	other   string
}

func newMediaFixture(t *testing.T, limit int) *mediaFixture {
	t.Helper()
	f := &mediaFixture{
		cfg: &config.Config{MaxMediaBytes: limit, MaxMessageBytes: 1 << 20, TokenTTL: time.Hour},
		st:  store.NewMemoryStore(), dir: t.TempDir(),
	}
	var err error
	f.blobs, err = blobstore.NewFileBlobStore(f.dir)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = f.blobs.Close(); _ = f.st.Close() })
	for _, id := range []string{"owner", "other"} {
		if err := f.st.CreateUser(context.Background(), &model.User{ID: id, Username: id}); err != nil {
			t.Fatal(err)
		}
		token, err := crypto.NewToken()
		if err != nil {
			t.Fatal(err)
		}
		if err := f.st.PutToken(context.Background(), crypto.HashToken(token), id, time.Now().Add(time.Hour)); err != nil {
			t.Fatal(err)
		}
		if id == "owner" {
			f.owner = token
		} else {
			f.other = token
		}
	}
	f.handler = NewServerWithBlobStore(f.cfg, f.st, ws.NewHub(), f.blobs).Handler
	return f
}

type mediaPart struct {
	name  string
	value []byte
	file  bool
	qp    bool
}

func multipartBody(t *testing.T, parts ...mediaPart) ([]byte, string) {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	for _, part := range parts {
		header := make(textproto.MIMEHeader)
		disposition := fmt.Sprintf(`form-data; name="%s"`, part.name)
		if part.file {
			disposition += `; filename="ciphertext.bin"`
			header.Set("Content-Type", "image/png")
		}
		header.Set("Content-Disposition", disposition)
		if part.qp {
			header.Set("Content-Transfer-Encoding", "quoted-printable")
		}
		w, err := mw.CreatePart(header)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := w.Write(part.value); err != nil {
			t.Fatal(err)
		}
	}
	if err := mw.Close(); err != nil {
		t.Fatal(err)
	}
	return body.Bytes(), mw.FormDataContentType()
}

func mediaRequest(handler http.Handler, method, path, token, contentType string, body []byte, chunked bool) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if chunked {
		req.ContentLength = -1
		req.TransferEncoding = []string{"chunked"}
	}
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	return w
}

func TestMediaRoundTrip(t *testing.T) {
	for _, tc := range []struct {
		name        string
		payload     []byte
		contentType string
		before      bool
		qp          bool
	}{
		{name: "binary-default", payload: []byte{0, 255, 128, '=', '\r', '\n'}},
		{name: "custom-before", payload: []byte("encrypted"), contentType: "video/mp4", before: true},
		{name: "custom-after", payload: []byte("encrypted"), contentType: "audio/ogg"},
		{name: "exact-limit", payload: bytes.Repeat([]byte{0xaa}, 256)},
		{name: "empty-file"},
		{name: "raw-transfer-encoding", payload: []byte("=41=00=FF\r\n"), qp: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newMediaFixture(t, 256)
			// Область видимости: без неё файл доступен только владельцу.
			parts := []mediaPart{
				{name: "file", value: tc.payload, file: true, qp: tc.qp},
				{name: "recipient_id", value: []byte("other")},
			}
			if tc.contentType != "" {
				field := mediaPart{name: "content_type", value: []byte(tc.contentType)}
				if tc.before {
					parts = append([]mediaPart{field}, parts...)
				} else {
					parts = append(parts, field)
				}
			}
			body, ct := multipartBody(t, parts...)
			w := mediaRequest(f.handler, "POST", "/v1/media", f.owner, ct, body, true)
			if w.Code != http.StatusCreated {
				t.Fatalf("upload: %d %s", w.Code, w.Body)
			}
			var got mediaResponse
			if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
				t.Fatal(err)
			}
			wantType := tc.contentType
			if wantType == "" {
				wantType = "application/octet-stream"
			}
			if len(got.ID) != 43 || got.Size != int64(len(tc.payload)) || got.ContentType != wantType {
				t.Fatalf("upload response: %#v", got)
			}
			metadata, err := f.st.GetMedia(context.Background(), got.ID)
			if err != nil || metadata.OwnerID != "owner" || metadata.CreatedAt.IsZero() || metadata.Size != got.Size {
				t.Fatalf("metadata: %#v, %v", metadata, err)
			}
			if metadata.RecipientID != "other" {
				t.Fatalf("область видимости не сохранилась: %#v", metadata)
			}
			// Адресат скачивает файл; посторонние — в TestMediaAccessControl.
			w = mediaRequest(f.handler, "GET", "/v1/media/"+got.ID, f.other, "", nil, false)
			if w.Code != http.StatusOK || !bytes.Equal(w.Body.Bytes(), tc.payload) {
				t.Fatalf("download: %d, bytes %x", w.Code, w.Body.Bytes())
			}
			if w.Header().Get("Content-Type") != wantType || w.Header().Get("Content-Length") != strconv.Itoa(len(tc.payload)) {
				t.Fatalf("download headers: %v", w.Header())
			}
			if w.Header().Get("X-Content-Type-Options") != "nosniff" || w.Header().Get("Content-Disposition") != "attachment" {
				t.Fatalf("unsafe download headers: %v", w.Header())
			}
			w = mediaRequest(f.handler, "HEAD", "/v1/media/"+got.ID, f.other, "", nil, false)
			if w.Code != http.StatusOK || w.Body.Len() != 0 {
				t.Fatalf("HEAD: %d, %s", w.Code, w.Body)
			}
		})
	}
}

func TestMediaUploadRejectionsAndCleanup(t *testing.T) {
	file := mediaPart{name: "file", file: true, value: []byte{0, 255, 1}}
	for _, tc := range []struct {
		name    string
		parts   []mediaPart
		status  int
		chunked bool
		mutate  string
	}{
		{name: "missing-file", status: 400},
		{name: "duplicate-file", parts: []mediaPart{file, file}, status: 400},
		{name: "unknown-field", parts: []mediaPart{file, {name: "secret_key", value: []byte("reject")}}, status: 400},
		{name: "invalid-mime", parts: []mediaPart{file, {name: "content_type", value: []byte("invalid")}}, status: 400},
		{name: "mime-injection", parts: []mediaPart{file, {name: "content_type", value: []byte("text/plain\r\nX-Test: x")}}, status: 400},
		{name: "duplicate-mime", parts: []mediaPart{file, {name: "content_type"}, {name: "content_type"}}, status: 400},
		{name: "mime-as-file", parts: []mediaPart{file, {name: "content_type", file: true}}, status: 400},
		{name: "oversized-mime", parts: []mediaPart{file, {name: "content_type", value: bytes.Repeat([]byte{'a'}, 1025)}}, status: 413},
		{name: "one-byte-over-limit", parts: []mediaPart{{name: "file", file: true, value: bytes.Repeat([]byte{0xff}, 257)}}, status: 413},
		{name: "chunked-over-limit", parts: []mediaPart{{name: "file", file: true, value: bytes.Repeat([]byte{0xff}, 257)}}, status: 413, chunked: true},
		{name: "truncated", parts: []mediaPart{file}, status: 400, mutate: "truncate", chunked: true},
		{name: "large-epilogue", parts: []mediaPart{file}, status: 413, mutate: "epilogue"},
		{name: "chunked-large-epilogue", parts: []mediaPart{file}, status: 413, mutate: "epilogue", chunked: true},
		{name: "not-multipart", parts: []mediaPart{file}, status: 400, mutate: "json"},
		{name: "multipart-mixed", parts: []mediaPart{file}, status: 400, mutate: "mixed"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newMediaFixture(t, 256)
			body, ct := multipartBody(t, tc.parts...)
			switch tc.mutate {
			case "truncate":
				body = body[:len(body)-20]
			case "epilogue":
				body = append(body, bytes.Repeat([]byte{'x'}, mediaMultipartOverhead+256)...)
			case "json":
				ct = "application/json"
			case "mixed":
				ct = strings.Replace(ct, "multipart/form-data", "multipart/mixed", 1)
			}
			w := mediaRequest(f.handler, "POST", "/v1/media", f.owner, ct, body, tc.chunked)
			if w.Code != tc.status {
				t.Fatalf("status: %d, want %d; %s", w.Code, tc.status, w.Body)
			}
			assertNoBlobs(t, f.dir)
		})
	}
}

func TestMediaAuthentication(t *testing.T) {
	f := newMediaFixture(t, 256)
	body, ct := multipartBody(t, mediaPart{name: "file", file: true, value: []byte("blob")})
	expired, err := crypto.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := f.st.PutToken(context.Background(), crypto.HashToken(expired), "owner", time.Now().Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	for _, token := range []string{"", "invalid", expired} {
		for _, method := range []string{"POST", "GET"} {
			path := "/v1/media"
			if method == "GET" {
				path += "/missing"
			}
			w := mediaRequest(f.handler, method, path, token, ct, body, false)
			if w.Code != http.StatusUnauthorized {
				t.Fatalf("%s unauthorized status: %d", method, w.Code)
			}
		}
	}
	assertNoBlobs(t, f.dir)
}

type mediaStoreFailure struct {
	store.Store
	saveErr error
	getErr  error
}

func (s mediaStoreFailure) SaveMedia(ctx context.Context, m *model.Media) error {
	if s.saveErr != nil {
		return s.saveErr
	}
	return s.Store.SaveMedia(ctx, m)
}

func (s mediaStoreFailure) GetMedia(ctx context.Context, id string) (*model.Media, error) {
	if s.getErr != nil {
		return nil, s.getErr
	}
	return s.Store.GetMedia(ctx, id)
}

type mediaBlobFailure struct {
	blobstore.BlobStore
	putErr error
	getErr error
}

func (b mediaBlobFailure) Put(id string, r io.Reader) error {
	if b.putErr != nil {
		return b.putErr
	}
	return b.BlobStore.Put(id, r)
}

func (b mediaBlobFailure) Get(id string) (io.ReadCloser, error) {
	if b.getErr != nil {
		return nil, b.getErr
	}
	return b.BlobStore.Get(id)
}

func TestMediaStorageFailures(t *testing.T) {
	failure := errors.New("storage unavailable")
	for _, tc := range []struct {
		name    string
		saveErr error
		putErr  error
		status  int
	}{
		{name: "metadata-failure", saveErr: failure, status: 500},
		{name: "metadata-conflict", saveErr: store.ErrConflict, status: 409},
		{name: "blob-failure", putErr: failure, status: 500},
		{name: "blob-conflict", putErr: store.ErrConflict, status: 409},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newMediaFixture(t, 256)
			st := mediaStoreFailure{Store: f.st, saveErr: tc.saveErr}
			blobs := mediaBlobFailure{BlobStore: f.blobs, putErr: tc.putErr}
			h := NewServerWithBlobStore(f.cfg, st, ws.NewHub(), blobs).Handler
			body, ct := multipartBody(t, mediaPart{name: "file", file: true, value: []byte("encrypted")})
			w := mediaRequest(h, "POST", "/v1/media", f.owner, ct, body, false)
			if w.Code != tc.status {
				t.Fatalf("upload: %d %s", w.Code, w.Body)
			}
			assertNoBlobs(t, f.dir)
		})
	}
	f := newMediaFixture(t, 256)
	if err := f.st.SaveMedia(context.Background(), &model.Media{ID: "missing-blob", OwnerID: "owner", ContentType: "application/octet-stream", Size: 4}); err != nil {
		t.Fatal(err)
	}
	// Токен владельца: проверяем отсутствие файла, а не отказ в доступе.
	for _, id := range []string{"missing-metadata", "missing-blob", "invalid.id"} {
		w := mediaRequest(f.handler, "GET", "/v1/media/"+id, f.owner, "", nil, false)
		if w.Code != 404 {
			t.Fatalf("missing %s: %d %s", id, w.Code, w.Body)
		}
	}
	for _, h := range []http.Handler{
		NewServerWithBlobStore(f.cfg, mediaStoreFailure{Store: f.st, getErr: failure}, ws.NewHub(), f.blobs).Handler,
		NewServerWithBlobStore(f.cfg, f.st, ws.NewHub(), mediaBlobFailure{BlobStore: f.blobs, getErr: failure}).Handler,
	} {
		w := mediaRequest(h, "GET", "/v1/media/missing-blob", f.owner, "", nil, false)
		if w.Code != 500 {
			t.Fatalf("internal failure: %d %s", w.Code, w.Body)
		}
	}
}

func TestLegacyConstructorAndDefaultLimit(t *testing.T) {
	f := newMediaFixture(t, 0)
	body, ct := multipartBody(t, mediaPart{name: "file", file: true, value: []byte("ciphertext")})
	w := mediaRequest(f.handler, "POST", "/v1/media", f.owner, ct, body, false)
	if w.Code != 201 {
		t.Fatalf("zero-value limit: %d %s", w.Code, w.Body)
	}
	h := NewServer(f.cfg, f.st, ws.NewHub()).Handler
	w = mediaRequest(h, "GET", "/healthz", "", "", nil, false)
	if w.Code != 200 {
		t.Fatalf("legacy health: %d", w.Code)
	}
	w = mediaRequest(h, "POST", "/v1/media", f.owner, ct, body, false)
	if w.Code != 503 {
		t.Fatalf("missing blob store: %d %s", w.Code, w.Body)
	}
}

// mediaUserToken создаёт пользователя и его токен.
func mediaUserToken(t *testing.T, f *mediaFixture, id string) string {
	t.Helper()
	if err := f.st.CreateUser(context.Background(), &model.User{ID: id, Username: id}); err != nil {
		t.Fatal(err)
	}
	token, err := crypto.NewToken()
	if err != nil {
		t.Fatal(err)
	}
	if err := f.st.PutToken(context.Background(), crypto.HashToken(token), id, time.Now().Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	return token
}

// uploadMedia загружает файл с заданной областью видимости.
func uploadScopedMedia(t *testing.T, handler http.Handler, token string, scope ...mediaPart) (int, string) {
	t.Helper()
	parts := append([]mediaPart{{name: "file", file: true, value: []byte("ciphertext")}}, scope...)
	body, ct := multipartBody(t, parts...)
	w := mediaRequest(handler, "POST", "/v1/media", token, ct, body, false)
	var got mediaResponse
	_ = json.Unmarshal(w.Body.Bytes(), &got)
	return w.Code, got.ID
}

// TestMediaAccessControl — знание id больше не даёт доступа к файлу:
// раньше любой авторизованный пользователь скачивал чужое вложение по id.
func TestMediaAccessControl(t *testing.T) {
	f := newMediaFixture(t, 256)
	third := mediaUserToken(t, f, "third")
	ctx := context.Background()

	// Личная переписка: файл видят владелец и адресат.
	code, id := uploadScopedMedia(t, f.handler, f.owner, mediaPart{name: "recipient_id", value: []byte("other")})
	if code != http.StatusCreated {
		t.Fatalf("загрузка с адресатом: %d", code)
	}
	for _, tc := range []struct {
		name  string
		token string
		want  int
	}{
		{"владелец", f.owner, http.StatusOK},
		{"адресат", f.other, http.StatusOK},
		{"посторонний", third, http.StatusNotFound},
	} {
		if w := mediaRequest(f.handler, "GET", "/v1/media/"+id, tc.token, "", nil, false); w.Code != tc.want {
			t.Fatalf("личный файл, %s: %d, ожидался %d", tc.name, w.Code, tc.want)
		}
	}

	// Группа: файл доступен участникам чата.
	chat := &model.Chat{ID: "chat-1", Type: model.ChatGroup, Title: "group", CreatedBy: "owner", CreatedAt: time.Now().UTC()}
	if err := f.st.CreateChat(ctx, chat); err != nil {
		t.Fatal(err)
	}
	if err := f.st.AddMember(ctx, chat.ID, "other", model.RoleMember); err != nil {
		t.Fatal(err)
	}
	code, id = uploadScopedMedia(t, f.handler, f.owner, mediaPart{name: "chat_id", value: []byte(chat.ID)})
	if code != http.StatusCreated {
		t.Fatalf("загрузка в чат: %d", code)
	}
	if w := mediaRequest(f.handler, "GET", "/v1/media/"+id, f.other, "", nil, false); w.Code != http.StatusOK {
		t.Fatalf("участник чата: %d", w.Code)
	}
	if w := mediaRequest(f.handler, "GET", "/v1/media/"+id, third, "", nil, false); w.Code != http.StatusNotFound {
		t.Fatalf("не участник чата: %d", w.Code)
	}

	// Неверная область видимости отвергается на загрузке.
	for _, tc := range []struct {
		name  string
		token string
		parts []mediaPart
		want  int
	}{
		{"чужой чат", third, []mediaPart{{name: "chat_id", value: []byte(chat.ID)}}, http.StatusForbidden},
		{"неизвестный чат", f.owner, []mediaPart{{name: "chat_id", value: []byte("nope")}}, http.StatusForbidden},
		{"неизвестный адресат", f.owner, []mediaPart{{name: "recipient_id", value: []byte("nobody")}}, http.StatusNotFound},
		{"две области", f.owner, []mediaPart{{name: "chat_id", value: []byte(chat.ID)}, {name: "recipient_id", value: []byte("other")}}, http.StatusBadRequest},
		{"дубль области", f.owner, []mediaPart{{name: "recipient_id", value: []byte("other")}, {name: "recipient_id", value: []byte("other")}}, http.StatusBadRequest},
	} {
		if code, _ := uploadScopedMedia(t, f.handler, tc.token, tc.parts...); code != tc.want {
			t.Fatalf("%s: %d, ожидался %d", tc.name, code, tc.want)
		}
	}

	// Аватар остаётся видимым всем: его рисуют в списках и профилях.
	code, avatarID := uploadScopedMedia(t, f.handler, f.owner)
	if code != http.StatusCreated {
		t.Fatalf("загрузка аватара: %d", code)
	}
	if w := mediaRequest(f.handler, "GET", "/v1/media/"+avatarID, third, "", nil, false); w.Code != http.StatusNotFound {
		t.Fatalf("файл без области видимости не должен открываться: %d", w.Code)
	}
	if err := f.st.SetAvatar(ctx, "owner", avatarID); err != nil {
		t.Fatal(err)
	}
	if w := mediaRequest(f.handler, "GET", "/v1/media/"+avatarID, third, "", nil, false); w.Code != http.StatusOK {
		t.Fatalf("аватар должен быть доступен: %d", w.Code)
	}

	// MEDIA_LEGACY_OPEN_ACCESS=1 возвращает прежнее поведение для старых файлов.
	legacyCfg := *f.cfg
	legacyCfg.MediaOpenAccess = true
	legacy := NewServerWithBlobStore(&legacyCfg, f.st, ws.NewHub(), f.blobs).Handler
	code, legacyID := uploadScopedMedia(t, legacy, f.owner)
	if code != http.StatusCreated {
		t.Fatalf("legacy-загрузка: %d", code)
	}
	if w := mediaRequest(legacy, "GET", "/v1/media/"+legacyID, third, "", nil, false); w.Code != http.StatusOK {
		t.Fatalf("legacy-доступ: %d", w.Code)
	}
	if w := mediaRequest(f.handler, "GET", "/v1/media/"+legacyID, third, "", nil, false); w.Code != http.StatusNotFound {
		t.Fatalf("без флага доступ должен быть закрыт: %d", w.Code)
	}
}

func assertNoBlobs(t *testing.T, dir string) {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil || len(entries) != 0 {
		t.Fatalf("orphan blobs: %v, %v", entries, err)
	}
}
