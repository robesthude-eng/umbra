package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"time"

	"umbra/server/internal/store"
	"umbra/server/internal/ws"
)

// «Прочитано» для личных чатов и для групп/каналов.
// В обоих случаях хранится курсор чтения, а не отметка на каждое сообщение:
// так данные растут по числу собеседников, а не по числу сообщений.
// {id} — собеседник для личного чата либо id группы.

type readCursorView struct {
	UserID string `json:"user_id"`
	ReadAt string `json:"read_at,omitempty"`
	Hidden bool   `json:"hidden"`
}

// readerView — один участник группы, который прочитал переписку.
type readerView struct {
	UserID string `json:"user_id"`
	ReadAt string `json:"read_at"`
}

type chatReadsView struct {
	ChatID  string       `json:"chat_id"`
	Members int          `json:"members"`
	Readers []readerView `json:"readers"`
}

type readRequest struct {
	ReadAt string `json:"read_at,omitempty"`
}

// readAtFromRequest — время прочтения из тела запроса (необязательного).
// Будущее отсекается: иначе клиент с уехавшими часами «прочитал бы» вперёд.
func readAtFromRequest(w http.ResponseWriter, r *http.Request) time.Time {
	at := time.Now().UTC()
	if r.Body == nil {
		return at
	}
	var body readRequest
	_ = json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(&body)
	if body.ReadAt == "" {
		return at
	}
	if parsed, err := time.Parse(time.RFC3339, body.ReadAt); err == nil && parsed.Before(at) {
		return parsed.UTC()
	}
	return at
}

func readStoreError(w http.ResponseWriter, err error) {
	if errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	writeError(w, http.StatusServiceUnavailable, "read receipts temporarily unavailable")
}

// handleMarkRead — POST /v1/chats/{id}/read.
func (s *Server) handleMarkRead(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	id := r.PathValue("id")
	if id == "" || id == userID {
		writeError(w, http.StatusBadRequest, "invalid chat id")
		return
	}
	at := readAtFromRequest(w, r)

	// Группа или канал: id — чат, в котором читатель состоит.
	if _, err := s.store.GetMember(r.Context(), id, userID); err == nil {
		if err := s.store.SetChatRead(r.Context(), id, userID, at); err != nil {
			readStoreError(w, err)
			return
		}
		// В группах прочтение — не тайна от участников, но скрывший статус себя не афиширует.
		if _, hidden, err := s.store.GetPresence(r.Context(), userID); err == nil && !hidden {
			s.fanoutChatRead(r, id, userID, at)
		}
		writeJSON(w, http.StatusOK, readCursorView{UserID: userID, ReadAt: at.Format(time.RFC3339)})
		return
	}

	// Личный чат: id — собеседник.
	if _, err := s.store.GetUserByID(r.Context(), id); err != nil {
		writeError(w, http.StatusNotFound, "chat not found")
		return
	}
	if err := s.store.SetReadCursor(r.Context(), userID, id, at); err != nil {
		readStoreError(w, err)
		return
	}
	// Скрывший «был(а) в сети» не сообщает и о прочтении: одна настройка на оба статуса.
	if _, hidden, err := s.store.GetPresence(r.Context(), userID); err == nil && !hidden {
		s.hub.Push(id, ws.Event{Type: "read", Data: map[string]string{
			"chat_id": userID,
			"read_at": at.Format(time.RFC3339),
		}})
	}
	writeJSON(w, http.StatusOK, readCursorView{UserID: userID, ReadAt: at.Format(time.RFC3339)})
}

// fanoutChatRead рассылает отметку о прочтении остальным участникам.
// Большие чаты пропускаются: там такая рассылка дороже пользы,
// клиент всё равно спрашивает список при открытии чата.
func (s *Server) fanoutChatRead(r *http.Request, chatID, readerID string, at time.Time) {
	members, err := s.store.ListMembers(r.Context(), chatID)
	if err != nil || len(members) > chatReadFanoutLimit {
		return
	}
	for _, m := range members {
		if m.UserID == readerID || !s.hub.Online(m.UserID) {
			continue
		}
		s.hub.Push(m.UserID, ws.Event{Type: "read", Data: map[string]string{
			"chat_id": chatID,
			"user_id": readerID,
			"read_at": at.Format(time.RFC3339),
		}})
	}
}

// chatReadFanoutLimit — выше этого размера группы push о прочтении не рассылается.
const chatReadFanoutLimit = 32

// handleGetRead — GET /v1/chats/{id}/read.
// Для личного чата: до какого момента собеседник прочитал переписку со мной.
// Для группы: список участников с их курсорами («кто прочитал»).
func (s *Server) handleGetRead(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)
	id := r.PathValue("id")
	if id == "" || id == userID {
		writeError(w, http.StatusBadRequest, "invalid chat id")
		return
	}

	if _, err := s.store.GetMember(r.Context(), id, userID); err == nil {
		reads, err := s.store.ListChatReads(r.Context(), id)
		if err != nil {
			readStoreError(w, err)
			return
		}
		out := chatReadsView{ChatID: id, Readers: make([]readerView, 0, len(reads))}
		if members, err := s.store.ListMembers(r.Context(), id); err == nil {
			out.Members = len(members)
		}
		for reader, at := range reads {
			if reader == userID {
				continue
			}
			if _, hidden, err := s.store.GetPresence(r.Context(), reader); err == nil && hidden {
				continue
			}
			out.Readers = append(out.Readers, readerView{UserID: reader, ReadAt: at.UTC().Format(time.RFC3339)})
		}
		// Стабильный порядок: сначала те, кто прочитал позже всех.
		sort.Slice(out.Readers, func(i, j int) bool {
			if out.Readers[i].ReadAt == out.Readers[j].ReadAt {
				return out.Readers[i].UserID < out.Readers[j].UserID
			}
			return out.Readers[i].ReadAt > out.Readers[j].ReadAt
		})
		writeJSON(w, http.StatusOK, out)
		return
	}

	at, err := s.store.GetReadCursor(r.Context(), id, userID)
	if err != nil {
		readStoreError(w, err)
		return
	}
	// Взаимность как у «был(а) в сети»: скрывший свой статус не видит чужой.
	hidden := false
	if _, h, err := s.store.GetPresence(r.Context(), id); err == nil {
		hidden = h
	}
	if !hidden {
		if _, h, err := s.store.GetPresence(r.Context(), userID); err == nil {
			hidden = h
		}
	}
	out := readCursorView{UserID: id, Hidden: hidden}
	if !hidden && !at.IsZero() {
		out.ReadAt = at.UTC().Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, out)
}
