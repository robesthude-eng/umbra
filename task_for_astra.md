# Задача для GPT-6 Astra: этап «Медиа и файлы» (шифрованные)

> Это единый самодостаточный бриф. Передай его модели ВМЕСТЕ с полным исходным кодом
> сервера (папка `server/` целиком). Модель должна вернуть zip-архив с обновлённым
> проектом и отчётом.

---

## 1. Твоя роль и контекст

Ты — senior Go-разработчик в проекте **Umbra** — приватном мессенджере (аналог
Telegram) с приоритетом на end-to-end шифрование. Сервер написан на **Go 1.27** и уже
содержит рабочий MVP: регистрацию по username + публичным ключам, challenge-response
аутентификацию (Ed25519), pre-key пакет для X3DH, отправку/получение зашифрованных
сообщений 1-на-1 и realtime-доставку по WebSocket.

**Железный архитектурный принцип:** сервер никогда не видит содержимое сообщений и
файлов. Он хранит только **ciphertext** и публичные ключи. Твоя задача должна соблюдать
этот принцип неукоснительно.

## 2. Как начать работу

1. Распакуй приложенный код `server/` и изучи его: `internal/config`, `internal/store`
   (интерфейс Store + реализация memory и postgres), `internal/httpapi` (хэндлеры и роуты
   в `server.go`), `internal/ws` (хаб), `internal/crypto`, `migrations/001_init.sql`,
   `docker-compose.yml`, `Dockerfile`, `Makefile`, `.env.example`, `README.md`.
2. Собери проект до внесения изменений, чтобы убедиться, что стартовая точка чистая:
   `go mod tidy && go build ./... && go vet ./... && go test ./...`
3. Выполни задачу из раздела 3, строго следуя конвенциям из раздела 4.
4. Проверь результат по чек-листу из раздела 5.
5. Упакуй результат и отчёт по правилам раздела 6.

## 3. Задача: загрузка и скачивание зашифрованных медиа (фото/видео/файлы/голосовые)

Добавь поддержку передачи медиа-файлов так, чтобы сервер хранил и передавал только
**шифрованный blob**, не имея доступа к содержимому.

### 3.1. Новый пакет `internal/blobstore`

Создай пакет `internal/blobstore` с интерфейсом хранилища бинарных блобов:

```go
type BlobStore interface {
    Put(id string, r io.Reader) error   // сохранить блоб под id
    Get(id string) (io.ReadCloser, error) // открыть блоб на чтение
    Delete(id string) error             // удалить блоб
    Close() error
}
```

Реализация **на файловой системе** (для теста и dev): `NewFileBlobStore(dir string)` —
каждый блоб сохраняется в файл `dir/<id>` (id — случайная строка из
`crypto.NewToken()`, поэтому path traversal невозможен; на всякий случай всё равно
валидируй id: только `[A-Za-z0-9_-]`). Директория создаётся при инициализации.

### 3.2. Метаданные медиа в хранилище

Расширь интерфейс `Store` (в `internal/store/store.go`) методами:

```go
SaveMedia(ctx context.Context, m *model.Media) error
GetMedia(ctx context.Context, id string) (*model.Media, error)
```

Добавь в `internal/model` структуру:

```go
type Media struct {
    ID          string    `json:"id"`
    OwnerID     string    `json:"owner_id"`
    ContentType string    `json:"content_type"` // mime, напр. application/octet-stream
    Size        int64     `json:"size"`
    CreatedAt   time.Time `json:"created_at"`
}
```

Реализуй методы в **обеих** реализациях Store (memory и postgres).

### 3.3. Миграция БД

Создай `migrations/002_media.sql`:

```sql
CREATE TABLE IF NOT EXISTS media (
    id           TEXT PRIMARY KEY,
    owner_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
    size         BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.4. Эндпоинты (добавь в `internal/httpapi`)

Подключи `blobstore` в `httpapi.Server` (передай через `NewServer` или отдельный
конструктор — сохрани обратную совместимость, обнови `cmd/server/main.go`).

**a) `POST /v1/media`** (требует Bearer-токен):
- multipart/form-data; поле `file` — сам зашифрованный blob; необязательное поле
  `content_type` — MIME-тип (по умолчанию `application/octet-stream`).
- Лимит размера — из конфига `MAX_MEDIA_BYTES` (добавь в `internal/config`, по
  умолчанию 50 MiB). При превышении — `413`.
- Генерирует `media_id`, сохраняет blob в blobstore и метаданные в Store.
- Ответ `201 {"id": "...", "content_type": "...", "size": N}`.

**b) `GET /v1/media/{id}`** (требует Bearer-токен):
- Отдаёт blob с `Content-Type` из метаданных и заголовком `Content-Length`.
- Если блоба нет — `404`.

> Доступ по `{id}` не выдаёт содержимого даже при утечке id: blob зашифрован на клиенте,
> и без E2E-ключа получателя он бесполезен. Это осознанное решение MVP (как в Signal:
> сервер — «тупой» ретранслятор блобов).

### 3.5. Конфигурация и окружение

- В `internal/config` добавь: `BLOB_DIR` (строка, по умолчанию `./data/blobs`),
  `MAX_MEDIA_BYTES` (int, по умолчанию `52428800`).
- Обнови `.env.example` и `docker-compose.yml` (проброс volume для blob-директории,
  переменные окружения).
- Обнови `README.md`: раздел «Что реализовано», таблицу API (два новых эндпоинта),
  раздел «Структура» (новый пакет), опиши поток работы с медиа.

## 4. Конвенции (обязательно соблюдай)

1. **Стиль и структура** — как в существующем коде: домен в `internal/model`, хранение
   в `internal/store`, HTTP в `internal/httpapi`, крипто-утилиты в `internal/crypto`.
2. **Ошибки** — использовать существующие `store.ErrNotFound`, `store.ErrConflict`;
   HTTP-ошибки через `writeError(w, code, msg)`; JSON через `writeJSON`.
3. **Кодирование** — бинарные данные в JSON только как base64 (функции `b64`/`b64e`
   уже есть в `internal/httpapi/handlers.go`); блобы медиа — НЕ base64, а сырые байты
   (multipart / body).
4. **Секреты** — только через env-переменные, никогда в коде.
5. **Комментарии** на русском, краткие, объясняющие решения по безопасности.
6. **Никакой самописной криптографии** — для генерации id используй
   `crypto.NewToken()`; медиа-шифрование происходит на клиенте, сервер его не касается.
7. Не ломай существующие эндпоинты и их поведение. Все старые smoke-кейсы должны
   продолжать работать.

## 5. Definition of Done (обязательно выполнить)

- [ ] `go build ./...` — без ошибок и предупреждений.
- [ ] `go vet ./...` — чисто.
- [ ] `go test ./...` — зелёный (напиши юнит-тесты для новых: `internal/blobstore`
      (put/get/delete/отсутствие), методов Store для media (memory), и хэндлеров
      upload/download через `httptest`).
- [ ] Ручная проверка (запиши в отчёт фактический вывод): подними сервер
      (`go run ./cmd/server`), выполни `curl`:
      1) register двух пользователей; 2) auth (challenge → verify) и получи токен;
      3) `POST /v1/media` с файлом; 4) `GET /v1/media/{id}` и сверь байты с оригиналом;
      5) негативный кейс: слишком большой файл → `413`, несуществующий id → `404`,
      запрос без токена → `401`.
- [ ] README и .env.example обновлены.

## 6. Формат ответа (что положить в zip)

Верни zip-архив с именем `server-media-update.zip`, содержащий:

1. **Полную обновлённую папку `server/`** (весь проект, со всеми изменениями —
   чтобы её можно было развернуть как есть). Не включай `bin/`, `.git/`, большие
   временные файлы и директорию `data/` с тестовыми блобами.
2. **`CHANGES.md`** в корне архива: список созданных/изменённых файлов, краткое
   описание каждого изменения, команды проверки и их фактический вывод, а также
   перечисление curl-команд, которые ты запускал для ручной проверки.

Перед упаковкой убедись, что `go build` проходит из чистой папки (без кэша
`bin/` и `data/`).
