# Задача для GPT-6 Astra: этап «Группы, каналы, контакты» (шифрованные)

> Единый самодостаточный бриф. Передай его модели ВМЕСТЕ с полным исходным кодом
> сервера (папка `server/` целиком из репозитория `robesthude-eng/umbra`, ветка `main`).
> Модель должна вернуть zip-архив с обновлённым проектом и отчётом.

---

## 1. Твоя роль и контекст

Ты — senior Go-разработчик в проекте **Umbra** — приватном мессенджере (аналог
Telegram) с приоритетом на end-to-end шифрование. Сервер на **Go 1.27** уже содержит:
регистрацию по username + публичным ключам, challenge-response аутентификацию (Ed25519),
pre-key пакет для X3DH, сообщения 1-на-1, realtime-доставку по WebSocket, и зашифрованные
медиа (`internal/blobstore`, `POST/GET /v1/media`).

**Железный архитектурный принцип:** сервер никогда не видит содержимое сообщений.
Он хранит и пересылает только **ciphertext**. Групповые сообщения сервер НЕ расшифровывает.

## 2. Как начать работу

1. Распакуй код `server/`, изучи: `internal/model`, `internal/store` (интерфейс + memory/postgres),
   `internal/httpapi` (server.go — роуты, handlers.go — хэндлеры, media.go — медиа),
   `internal/ws` (hub.go — `Hub.Push(userID, event)`), `internal/crypto`, `migrations/`.
2. Собери чистую точку: `go mod tidy && go build ./... && go vet ./... && go test ./...`
3. Выполни задачу из §3, соблюдая конвенции §4.
4. Проверь по чек-листу §5.
5. Упакуй по правилам §6.

## 3. Задача

Добавь групповые чаты, каналы, контакты и индикатор «печатает». Всё — на зашифрованных
сообщениях: сервер только хранит и рассылает ciphertext, групповые ключи (Sender Keys /
MLS) живут на клиентах и через этот API не передаются.

### 3.1. Модель данных (дополни `internal/model`)

```go
type ChatType string
const (ChatGroup ChatType = "group"; ChatChannel ChatType = "channel")

type Chat struct {
    ID        string    `json:"id"`
    Type      ChatType  `json:"type"` // group | channel
    Title     string    `json:"title"`
    CreatedBy string    `json:"created_by"`
    CreatedAt time.Time `json:"created_at"`
}

type MemberRole string
const (RoleOwner MemberRole = "owner"; RoleAdmin MemberRole = "admin"; RoleMember MemberRole = "member")

type ChatMember struct {
    ChatID   string     `json:"chat_id"`
    UserID   string     `json:"user_id"`
    Role     MemberRole `json:"role"`
    JoinedAt time.Time  `json:"joined_at"`
}

type Contact struct {
    UserID    string    `json:"user_id"`
    ContactID string    `json:"contact_id"`
    CreatedAt time.Time `json:"created_at"`
}
```

**Групповое сообщение:** переиспользуй `model.Message`, добавив поле `ChatID string`
(для DM остаётся `RecipientID`). Сервер не различает содержимое — только маршрутизирует.
Признак адресации: если `ChatID != ""` — групповое, иначе — личное (`RecipientID`).

### 3.2. Хранилище (расширь `store.Store` и обе реализации)

```go
CreateChat(ctx, *model.Chat) error
GetChat(ctx, chatID string) (*model.Chat, error)
AddMember(ctx, chatID, userID string, role model.MemberRole) error
RemoveMember(ctx, chatID, userID string) error
ListMembers(ctx, chatID string) ([]*model.ChatMember, error)
ListChatsForUser(ctx, userID string) ([]*model.Chat, error)

AddContact(ctx, userID, contactID string) error
ListContacts(ctx, userID string) ([]string, error)

SaveMessage(ctx, *model.Message) error   // уже есть — расширь под ChatID
ListMessages(ctx, userID string, since time.Time) ([]*model.Message, error) // уже есть — дополни групповой выборкой
```

Правила:
- `CreateChat` создаёт чат и сразу добавляет создателя как `owner` (атомарно, в транзакции для postgres).
- `AddMember` на существующего участника → `ErrConflict` (или no-op по твоему выбору — обоснуй).
- `RemoveMember` владельца запрещён (верни ошибку).
- `ListChatsForUser` возвращает чаты, где пользователь участник (для channels — чаты, где он подписан).
- Групповое сообщение сохраняется один раз, а при доставке идёт рассылка всем участникам
  через WS-хаб (НЕ копия на каждого в БД — иначе дублирование). `ListMessages` должен
  возвращать и личные, и групповые сообщения, адресованные пользователю.

### 3.3. Миграция `migrations/003_groups.sql`

```sql
CREATE TABLE IF NOT EXISTS chats (
    id         TEXT PRIMARY KEY,
    type       TEXT NOT NULL CHECK (type IN ('group','channel')),
    title      TEXT NOT NULL,
    created_by TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS chat_members (
    chat_id   TEXT NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id   TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role      TEXT NOT NULL DEFAULT 'member',
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, user_id)
);
CREATE INDEX IF NOT EXISTS chat_members_user_idx ON chat_members(user_id);
ALTER TABLE messages ADD COLUMN IF NOT EXISTS chat_id TEXT;
CREATE INDEX IF NOT EXISTS messages_chat_idx ON messages(chat_id, created_at);
CREATE TABLE IF NOT EXISTS contacts (
    user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    contact_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, contact_id)
);
```

> `ALTER TABLE messages ADD COLUMN IF NOT EXISTS` — сохрани обратную совместимость с 001/002.

### 3.4. Эндпоинты (в `internal/httpapi`)

Все требуют Bearer-токен (`requireAuth`), `userID` из контекста `ctxUserID`.

| Метод | Путь | Описание |
|---|---|---|
| POST | `/v1/groups` | создать group; body `{title}` |
| POST | `/v1/channels` | создать channel; body `{title}` |
| GET | `/v1/chats` | список чатов текущего пользователя |
| POST | `/v1/chats/{id}/members` | добавить участника; body `{user_id}` (для channel — самоподписка) |
| DELETE | `/v1/chats/{id}/members/{user_id}` | удалить участника (только owner/admin) |
| GET | `/v1/chats/{id}/members` | список участников |
| POST | `/v1/chats/{id}/messages` | отправить сообщение в чат; body `{ciphertext}` |
| POST | `/v1/contacts` | добавить контакт; body `{contact_id}` |
| GET | `/v1/contacts` | список контактов |
| POST | `/v1/chats/{id}/typing` | отметить «печатает» (эфемерно, TTL ~5 с) |
| GET | `/v1/chats/{id}/typing` | кто сейчас печатает |

Правила:
- Отправлять сообщение в чат может только участник; в channel — только owner/admin (для MVP).
- `POST /v1/chats/{id}/messages` сохраняет сообщение и **рассылает его всем участникам
  через `hub.Push(userID, ws.Event{Type:"message", Data:...})`** (включая отправителя —
  для синхронизации multi-device). Получатели берут сообщение и по REST, и по WS.
- «Печатает» — in-memory с TTL (как `challengeStore`), НЕ в БД, без логов «кто когда».
- Проверки прав: неучастник не может писать/читать чат (404, чтобы не раскрывать существование).

### 3.5. Обнови документацию

- `README.md`: раздел «Что реализовано», таблица API (новые эндпоинты), поток групповых сообщений.
- Обнови `docs/api.md`, если он уже есть (иначе — создай краткий).
- `.env.example` — без изменений (новых env не требуется).

## 4. Конвенции (обязательно)

1. Стиль и структура как в существующем коде; домен в `internal/model`, хранение в `internal/store`, HTTP в `internal/httpapi`.
2. Ошибки: `store.ErrNotFound` / `store.ErrConflict`; HTTP через `writeError`/`writeJSON`; бинарные данные — base64 (`b64`/`b64e`).
3. ID генерируй через `crypto.NewToken()`; секреты — только env; комментарии на русском.
4. Никакой самописной криптографии; сервер не расшифровывает групповые сообщения.
5. Не ломай существующие эндпоинты. Все старые тесты должны проходить.

## 5. Definition of Done

- [ ] `go build ./...`, `go vet ./...` — чисто.
- [ ] `go test ./...` и `go test -race ./...` — зелёный. Напиши тесты для: model/store (memory) —
      создание чата + owner, конфликт участника, запрет удаления owner, рассылка через хаб;
      httpapi — создание группы/канала, добавление/удаление участника, отправка+получение
      группового сообщения, права (неучастник → 404), контакты, typing.
- [ ] Ручная curl-проверка (зафиксируй фактический вывод): создать группу, добавить двух
      участников, отправить сообщение, получить его у второго участника, негативные кейсы
      (неучастник, без токена).
- [ ] README обновлён.

## 6. Формат ответа

Zip `server-groups-update.zip`:
1. Полную обновлённую папку `server/` (без `bin/`, `.git/`, `data/`).
2. `CHANGES.md` в корне: созданные/изменённые файлы, описание, команды проверки и их
   фактический вывод, curl-команды ручной проверки.

Перед упаковкой — `go build` из чистой папки (без `bin/`, `data/`).
