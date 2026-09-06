package httpapi

import (
	"errors"
	"io"
	"log"
	"math"
	"mime"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/crypto"
	"umbra/server/internal/model"
	"umbra/server/internal/store"
)

const (
	defaultMediaContentType  = "application/octet-stream"
	maxMediaContentTypeBytes = 1024
	mediaMultipartOverhead   = 64 << 10
)

type mediaResponse struct {
	ID          string `json:"id"`
	ContentType string `json:"content_type"`
	Size        int64  `json:"size"`
}

// observedReader отличает ошибку тела запроса от ошибки диска в BlobStore.Put.
type observedReader struct {
	io.Reader
	err error
}

func (r *observedReader) Read(p []byte) (int, error) {
	n, err := r.Reader.Read(p)
	if err != nil && err != io.EOF {
		r.err = err
	}
	return n, err
}

func (s *Server) handleUploadMedia(w http.ResponseWriter, r *http.Request) {
	if s.blobs == nil {
		writeError(w, http.StatusServiceUnavailable, "media storage unavailable")
		return
	}
	limit := int64(s.cfg.MaxMediaBytes)
	if limit <= 0 {
		limit = config.DefaultMaxMediaBytes
	}
	// Отдельный бюджет обвязки не отнимает байты у допустимого файла.
	requestLimit := int64(math.MaxInt64)
	if limit <= math.MaxInt64-mediaMultipartOverhead {
		requestLimit = limit + mediaMultipartOverhead
	}
	r.Body = http.MaxBytesReader(w, r.Body, requestLimit)
	if r.ContentLength > requestLimit {
		writeError(w, http.StatusRequestEntityTooLarge, "media too large")
		return
	}
	requestType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || requestType != "multipart/form-data" {
		writeError(w, http.StatusBadRequest, "expected multipart/form-data")
		return
	}
	mr, err := r.MultipartReader()
	if err != nil {
		writeError(w, http.StatusBadRequest, "expected multipart/form-data")
		return
	}
	id, err := crypto.NewToken()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	contentType := defaultMediaContentType
	var size int64
	var fileSeen, contentTypeSeen, blobSaved, committed bool
	defer func() {
		if blobSaved && !committed {
			if err := s.blobs.Delete(id); err != nil && !errors.Is(err, store.ErrNotFound) {
				log.Printf("не удалось удалить незавершённый blob: %v", err)
			}
		}
	}()
	for {
		// NextRawPart не декодирует quoted-printable: ciphertext должен остаться побайтно тем же.
		part, err := mr.NextRawPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			writeMediaReadError(w, err)
			return
		}
		switch part.FormName() {
		case "file":
			if fileSeen {
				writeError(w, http.StatusBadRequest, "exactly one file is required")
				return
			}
			fileSeen = true
			source := &observedReader{Reader: part}
			limited := &io.LimitedReader{R: source, N: limit}
			if err := s.blobs.Put(id, limited); err != nil {
				if source.err != nil {
					writeMediaReadError(w, source.err)
				} else if errors.Is(err, store.ErrConflict) {
					writeError(w, http.StatusConflict, "media id conflict")
				} else {
					writeError(w, http.StatusInternalServerError, "internal error")
				}
				return
			}
			blobSaved = true
			size = limit - limited.N
			// Читаем один лишний байт, чтобы отличить точный лимит от превышения.
			n, err := io.CopyN(io.Discard, part, 1)
			if n > 0 {
				writeError(w, http.StatusRequestEntityTooLarge, "media too large")
				return
			}
			if err != io.EOF {
				writeMediaReadError(w, err)
				return
			}
		case "content_type":
			if contentTypeSeen || part.FileName() != "" {
				writeError(w, http.StatusBadRequest, "invalid content_type field")
				return
			}
			contentTypeSeen = true
			value, err := io.ReadAll(io.LimitReader(part, maxMediaContentTypeBytes+1))
			if err != nil {
				writeMediaReadError(w, err)
				return
			}
			if len(value) > maxMediaContentTypeBytes {
				writeError(w, http.StatusRequestEntityTooLarge, "content_type too large")
				return
			}
			contentType, err = parseMediaContentType(string(value))
			if err != nil {
				writeError(w, http.StatusBadRequest, "invalid content_type")
				return
			}
		default:
			writeError(w, http.StatusBadRequest, "unexpected multipart field")
			return
		}
	}
	// Учитываем и эпилог после закрывающей boundary, в том числе при chunked-загрузке.
	if _, err := io.Copy(io.Discard, r.Body); err != nil {
		writeMediaReadError(w, err)
		return
	}
	if !fileSeen {
		writeError(w, http.StatusBadRequest, "file is required")
		return
	}
	m := &model.Media{
		ID: id, OwnerID: r.Context().Value(ctxUserID).(string),
		ContentType: contentType, Size: size, CreatedAt: time.Now().UTC(),
	}
	if err := s.store.SaveMedia(r.Context(), m); err != nil {
		if errors.Is(err, store.ErrConflict) {
			writeError(w, http.StatusConflict, "media id conflict")
		} else {
			writeError(w, http.StatusInternalServerError, "internal error")
		}
		return
	}
	committed = true
	writeJSON(w, http.StatusCreated, mediaResponse{ID: id, ContentType: contentType, Size: size})
}

func (s *Server) handleDownloadMedia(w http.ResponseWriter, r *http.Request) {
	if s.blobs == nil {
		writeError(w, http.StatusServiceUnavailable, "media storage unavailable")
		return
	}
	m, err := s.store.GetMedia(r.Context(), r.PathValue("id"))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "media not found")
		} else {
			writeError(w, http.StatusInternalServerError, "internal error")
		}
		return
	}
	// По контракту MVP любой авторизованный получатель с id может получить ciphertext.
	blob, err := s.blobs.Get(m.ID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) || errors.Is(err, blobstore.ErrInvalidID) {
			writeError(w, http.StatusNotFound, "media not found")
		} else {
			writeError(w, http.StatusInternalServerError, "internal error")
		}
		return
	}
	defer blob.Close()
	w.Header().Set("Content-Type", m.ContentType)
	w.Header().Set("Content-Length", strconv.FormatInt(m.Size, 10))
	w.Header().Set("Content-Disposition", "attachment")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Cache-Control", "private, no-store")
	w.WriteHeader(http.StatusOK)
	if r.Method == http.MethodHead {
		return
	}
	if _, err := io.CopyN(w, blob, m.Size); err != nil {
		log.Printf("ошибка передачи blob: %v", err)
		// После отправки заголовков нельзя добавлять JSON к шифротексту.
		panic(http.ErrAbortHandler)
	}
}

func writeMediaReadError(w http.ResponseWriter, err error) {
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) || errors.Is(err, multipart.ErrMessageTooLarge) {
		writeError(w, http.StatusRequestEntityTooLarge, "media too large")
		return
	}
	writeError(w, http.StatusBadRequest, "invalid multipart body")
}

func parseMediaContentType(value string) (string, error) {
	if strings.ContainsAny(value, "\r\n") {
		return "", errors.New("invalid MIME type")
	}
	value = strings.TrimSpace(value)
	if value == "" {
		return defaultMediaContentType, nil
	}
	mediaType, params, err := mime.ParseMediaType(value)
	if err != nil || !strings.Contains(mediaType, "/") || strings.Contains(mediaType, "*") {
		return "", errors.New("invalid MIME type")
	}
	return mime.FormatMediaType(mediaType, params), nil
}
