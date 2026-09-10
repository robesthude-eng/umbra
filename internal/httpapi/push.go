package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"umbra/server/internal/config"
	"umbra/server/internal/model"
	"umbra/server/internal/push"
	"umbra/server/internal/store"
)

const (
	maxPushBodyBytes = 4 << 10
	maxPushTokenLen  = 512
	// pushTimeout — общий бюджет на рассылку по устройствам одного человека.
	pushTimeout = 20 * time.Second
	// Сообщение подождёт выключенный телефон, а звонок — нет: звонок,
	// доехавший через десять минут, только напугает.
	messagePushTTL = 12 * time.Hour
	callPushTTL    = 45 * time.Second
)

// pushSender — то, что умеет отправлять уведомление. В продакшене это
// *push.FCM, в тестах — заглушка; nil означает «push выключен».
type pushSender interface {
	Send(ctx context.Context, m push.Message) error
}

// newPusher собирает отправителя из конфигурации. Любая ошибка — это запись
// в лог и работа без уведомлений: мессенджер важнее push’ей.
func newPusher(cfg *config.Config) pushSender {
	if cfg == nil {
		return nil
	}
	credentials := strings.TrimSpace(cfg.FCMKeyJSON)
	if credentials == "" && strings.TrimSpace(cfg.FCMKeyFile) != "" {
		raw, err := os.ReadFile(cfg.FCMKeyFile)
		if err != nil {
			log.Printf("push: cannot read %s: %v; notifications disabled", cfg.FCMKeyFile, err)
			return nil
		}
		credentials = string(raw)
	}
	if credentials == "" {
		return nil
	}
	sender, err := push.NewFCM([]byte(credentials), strings.TrimSpace(cfg.FCMProjectID))
	if err != nil {
		log.Printf("push: %v; notifications disabled", err)
		return nil
	}
	log.Printf("push: FCM enabled for project %s", sender.ProjectID())
	return sender
}

// pushDeviceRequest — тело запроса регистрации/удаления токена.
type pushDeviceRequest struct {
	Token    string `json:"token"`
	Platform string `json:"platform"`
}

// handleRegisterPushDevice — POST /v1/push/devices. Клиент присылает токен FCM
// после входа и при каждой его смене.
func (s *Server) handleRegisterPushDevice(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)

	var req pushDeviceRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxPushBodyBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	token := strings.TrimSpace(req.Token)
	if token == "" || len(token) > maxPushTokenLen {
		writeError(w, http.StatusBadRequest, "invalid token")
		return
	}
	platform := strings.TrimSpace(req.Platform)
	if platform == "" {
		platform = "android"
	}
	if platform != "android" {
		writeError(w, http.StatusBadRequest, "unsupported platform")
		return
	}

	if err := s.store.SavePushDevice(r.Context(), userID, token, platform); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "user not found")
			return
		}
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	// push_enabled говорит клиенту, настроен ли на сервере Firebase: без этого
	// токен хранится, но уведомлений не будет.
	writeJSON(w, http.StatusOK, map[string]any{
		"status":       "registered",
		"push_enabled": s.pusher != nil,
	})
}

// handleDeletePushDevice — DELETE /v1/push/devices. Вызывается перед выходом
// из аккаунта, чтобы чужие уведомления не приходили на этот телефон.
func (s *Server) handleDeletePushDevice(w http.ResponseWriter, r *http.Request) {
	userID := r.Context().Value(ctxUserID).(string)

	var req pushDeviceRequest
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxPushBodyBytes)).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	token := strings.TrimSpace(req.Token)
	if token == "" || len(token) > maxPushTokenLen {
		writeError(w, http.StatusBadRequest, "invalid token")
		return
	}

	// Удаляем только свой токен: чужой человек не должен глушить чужой телефон.
	devices, err := s.store.ListPushDevices(r.Context(), userID)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	found := false
	for _, d := range devices {
		if d.Token == token {
			found = true
			break
		}
	}
	if !found {
		writeError(w, http.StatusNotFound, "token not found")
		return
	}
	if err := s.store.DeletePushDevice(r.Context(), token); err != nil && !errors.Is(err, store.ErrNotFound) {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "deleted"})
}

// notifyMessage — «пришло сообщение» для того, кого нет в сети.
// Текст сообщения в уведомление не кладём: клиент сам заберёт его из чата.
func (s *Server) notifyMessage(recipientID, senderID, chatID, messageID string) {
	if s.pusher == nil || recipientID == "" || recipientID == senderID {
		return
	}
	if s.hub.Online(recipientID) {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), pushTimeout)
		defer cancel()

		data := map[string]string{
			"kind":       "message",
			"sender_id":  senderID,
			"message_id": messageID,
		}
		if chatID != "" {
			data["chat_id"] = chatID
		}
		if name := s.pushDisplayName(ctx, senderID); name != "" {
			data["sender_name"] = name
		}
		collapse := "umbra-message-" + senderID
		if chatID != "" {
			collapse = "umbra-chat-" + chatID
		}
		s.deliverPush(ctx, recipientID, push.Message{
			Data:        data,
			CollapseKey: collapse,
			TTL:         messagePushTTL,
		})
	}()
}

// notifyIncomingCall будит телефон приглашённого: высокий приоритет и короткий TTL.
// В групповом звонке вызывается по разу на каждого участника, кроме звонящего.
func (s *Server) notifyIncomingCall(call *model.Call, calleeID string) {
	if s.pusher == nil || call == nil || calleeID == "" {
		return
	}
	if s.hub.Online(calleeID) {
		// Приложение открыто — экран звонка покажет событие по WebSocket.
		return
	}
	callID, callerID, video := call.ID, call.CallerID, call.Video
	// Сколько всего людей в звонке — экран входящего покажет «групповой звонок».
	participants := len(call.Everyone())
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), pushTimeout)
		defer cancel()

		data := map[string]string{
			"kind":         "call",
			"call_id":      callID,
			"caller_id":    callerID,
			"video":        strconv.FormatBool(video),
			"participants": strconv.Itoa(participants),
		}
		if name := s.pushDisplayName(ctx, callerID); name != "" {
			data["caller_name"] = name
		}
		s.deliverPush(ctx, calleeID, push.Message{
			Data:         data,
			CollapseKey:  "umbra-call-" + callID,
			HighPriority: true,
			TTL:          callPushTTL,
		})
	}()
}

// notifyCallEnded гасит экран входящего на телефоне, который разбудили звонком:
// без этого он будет звонить после того, как звонящий уже бросил трубку.
func (s *Server) notifyCallEnded(userID, callID, status string) {
	if s.pusher == nil || userID == "" {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), pushTimeout)
		defer cancel()

		s.deliverPush(ctx, userID, push.Message{
			Data: map[string]string{
				"kind":    "call_ended",
				"call_id": callID,
				"status":  status,
			},
			CollapseKey:  "umbra-call-" + callID,
			HighPriority: true,
			TTL:          callPushTTL,
		})
	}()
}

// deliverPush рассылает уведомление на все устройства пользователя и чистит
// мёртвые токены. Ошибки только логируются: уведомление — вспомогательный
// канал, основной обмен идёт по WebSocket.
func (s *Server) deliverPush(ctx context.Context, userID string, m push.Message) {
	devices, err := s.store.ListPushDevices(ctx, userID)
	if err != nil {
		log.Printf("push: cannot list devices for %s: %v", userID, err)
		return
	}
	for _, d := range devices {
		m.Token = d.Token
		switch err := s.pusher.Send(ctx, m); {
		case err == nil:
		case errors.Is(err, push.ErrTokenInvalid):
			if delErr := s.store.DeletePushDevice(ctx, d.Token); delErr != nil && !errors.Is(delErr, store.ErrNotFound) {
				log.Printf("push: cannot delete stale token: %v", delErr)
			}
		default:
			log.Printf("push: send failed for %s: %v", userID, err)
		}
	}
}

// pushDisplayName — как подписать уведомление. Если имени нет, клиент
// покажет нейтральный текст вроде «Новое сообщение».
func (s *Server) pushDisplayName(ctx context.Context, userID string) string {
	u, err := s.store.GetUserByID(ctx, userID)
	if err != nil || u == nil {
		return ""
	}
	name := strings.TrimSpace(strings.TrimSpace(u.DisplayName) + " " + strings.TrimSpace(u.LastName))
	if name == "" {
		name = strings.TrimSpace(u.Username)
	}
	if len(name) > 64 {
		name = name[:64]
	}
	return name
}
