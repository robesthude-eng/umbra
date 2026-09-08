// Командная точка входа сервера Umbra.
package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"umbra/server/internal/blobstore"
	"umbra/server/internal/config"
	"umbra/server/internal/httpapi"
	"umbra/server/internal/maintenance"
	"umbra/server/internal/store"
	"umbra/server/internal/telegram"
	"umbra/server/internal/ws"
)

// tgOTPSender доставляет OTP-код в Telegram-чат, привязанный к номеру.
type tgOTPSender struct {
	cl *telegram.Client
}

func (t tgOTPSender) SendCode(ctx context.Context, phone string, chatID int64, code string) error {
	return t.cl.SendMessage(ctx, chatID,
		fmt.Sprintf("Umbra: код для номера %s — %s. Действует 5 минут.", phone, code))
}

func main() {
	cfg := config.Load()
	if err := cfg.Validate(); err != nil {
		log.Fatal(err)
	}

	var st store.Store
	var err error
	if cfg.Store == "postgres" {
		st, err = store.NewPostgresStore(context.Background(), cfg.DatabaseURL)
		if err != nil {
			log.Fatalf("не удалось подключиться к PostgreSQL: %v", err)
		}
		log.Printf("используется PostgreSQL")
	} else {
		st = store.NewMemoryStore()
		log.Printf("используется in-memory хранилище (для продакшна задайте STORE=postgres)")
	}
	defer st.Close()

	var blobs blobstore.BlobStore
	if cfg.BlobStoreType == "s3" {
		blobs, err = blobstore.NewS3BlobStore(cfg.S3Endpoint, cfg.S3AccessKey, cfg.S3SecretKey, cfg.S3Bucket, cfg.S3Region, cfg.S3UseSSL)
		log.Printf("используется S3-хранилище блобов (бакет %q)", cfg.S3Bucket)
	} else {
		blobs, err = blobstore.NewFileBlobStore(cfg.BlobDir)
		log.Printf("используется файловое хранилище блобов (%s)", cfg.BlobDir)
	}
	if err != nil {
		log.Fatalf("не удалось открыть хранилище медиа: %v", err)
	}
	defer blobs.Close()

	hub := ws.NewHub()
	go hub.Run()
	background, stopBackground := context.WithCancel(context.Background())
	maintenanceDone := make(chan struct{})
	go func() {
		defer close(maintenanceDone)
		maintenance.Run(background, st, blobs)
	}()

	// Доставка OTP-кодов через Telegram-бота (если задан токен).
	// ВАЖНО: у бота должен быть один слушатель — если его опрашивает что-то ещё
	// (n8n), второй getUpdates-цикл получит конфликт; отключите другой опрос.
	var otpSender httpapi.OTPSender
	if cfg.TelegramBotToken != "" {
		tg := telegram.NewClient(cfg.TelegramBotToken)
		otpSender = tgOTPSender{cl: tg}
		go tg.StartPolling(background, func(ctx context.Context, raw string, chatID int64) (bool, error) {
			phone, err := httpapi.NormalizePhone(raw)
			if err != nil {
				return false, nil // сообщение не похоже на номер — игнорируем
			}
			if err := st.BindTelegram(ctx, phone, chatID); err != nil {
				return false, err
			}
			log.Printf("telegram: номер %s привязан к chat %d", phone, chatID)
			return true, nil
		}, "umbra")
		log.Printf("Telegram-бот для OTP-кодов включён")
	} else {
		log.Printf("TELEGRAM_BOT_TOKEN не задан: вход/регистрация по коду выключены")
	}

	srv := httpapi.NewServerForMain(cfg, st, hub, blobs, otpSender)

	go func() {
		log.Printf("Umbra сервер запущен на %s", cfg.ListenAddr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("ошибка сервера: %v", err)
		}
	}()

	// Сначала завершаем запросы, затем закрываем хранилища.
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Printf("завершение работы...")
	shutdown, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdown); err != nil {
		_ = srv.Close()
	}
	stopBackground()
	hub.Close()
	select {
	case <-maintenanceDone:
	case <-time.After(35 * time.Second):
		log.Printf("maintenance shutdown timed out")
	}
}
