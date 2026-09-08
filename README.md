# Umbra

Мессенджер с регистрацией по публичному ключу, Go-сервером и Android-клиентом
на Kotlin/Compose. Сервер принимает непрозрачный ciphertext. Личные текстовые
сообщения Android шифрует через libsignal 0.60.1.

Проект находится в разработке. Состояние исправлений и выполненных проверок —
в [CHANGES.md](CHANGES.md). Серверный API и готовность клиентской функции —
разные вещи: наличие маршрута звонков не означает наличия WebRTC в приложении.

## Состав

| Область | Реализация |
|---|---|
| Аккаунт | Имя + номер телефона (без SMS/e-mail) + Ed25519 challenge-response; вход по номеру; отзыв токена при выходе; legacy-вход по username сохранён |
| Поиск контактов | Телефонная книга сопоставляется по SHA-256-хэшам номеров (POST /v1/contacts/discover); номера в открытом виде на сервер не уходят |
| Личная переписка Android | Signal, постоянная очередь отправки, локальная зашифрованная история; E2E-медиа — фото/файлы (AES-256-GCM) |
| Доставка | WebSocket с переподключением, постраничная REST-синхронизация, защита повторов по client_message_id |
| Серверное хранилище | PostgreSQL; memory для разработки |
| Файлы на сервере | File/S3, лимит размера, атомарная квота, очистка после удаления аккаунта |
| Группы и каналы | Серверные роли и доставка ciphertext; групповое E2E на Android не реализовано |
| Звонки | Серверный сигналинг; WebRTC на Android не реализован |
| Таймер | API expires_in, удаление истёкших сообщений на сервере и Android; выбор таймера — в поле ввода сообщения (иконка таймера) |

## Стек и структура

Go 1.27, PostgreSQL 16, Docker Compose; Android SDK 35, JDK 17, Kotlin 2.0.20,
Compose, Room 2.6.1, OkHttp/Retrofit, libsignal 0.60.1. Существующие версии основных
зависимостей сохранены.

| Путь | Назначение |
|---|---|
| cmd/server | Запуск сервера и фоновой очистки |
| cmd/gc | Dry-run и явное удаление старых осиротевших файлов |
| internal/httpapi | REST и WebSocket |
| internal/store | Memory/PostgreSQL, атомарные операции и квитанции повторов |
| internal/blobstore | Зашифрованные файлы на диске или S3 |
| internal/maintenance | Очистка сроков хранения и очередь удаления файлов |
| migrations | SQL-миграции 001–007 |
| android | Клиент, Room-миграция, instrumented-тесты |
| docs/api.md | Контракт API, включая ключи v2 |
| docs/deploy.md | Запуск, обновление БД, резервное копирование |
| scripts | Существующая smoke-проверка медиа |
| .github/workflows/checks.yml | Проверки Go/PostgreSQL и Android API 26/35 |

## Локальный сервер

```bash
make run
# http://localhost:8080
```

По умолчанию STORE=memory, BLOB_DIR=./data/blobs. После перезапуска memory теряет
аккаунты, метаданные и токены. Файлы на диске остаются, но без метаданных недоступны
через API. Для сохраняемых данных используйте PostgreSQL. Прямой go run не читает
.env автоматически.

## PostgreSQL через Compose

```bash
cp .env.example .env
# Задайте собственный POSTGRES_PASSWORD; для Compose DSN подходит hex-пароль.
docker compose up -d --build
```

API доступен на 127.0.0.1:8080, данные БД и файлов — в томах pgdata и blobdata.
MinIO включается отдельно профилем s3; файловому режиму его секреты не нужны.

**Существующей БД нужна миграция до запуска нового сервера.** Если уже применены
001–005:

```bash
docker compose stop server
docker compose up -d db
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < migrations/006_integrity.sql
docker compose exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' < migrations/007_phone.sql
docker compose up -d --build server
```

Если применена 006, но нет 007 — достаточно выполнить только последнюю команду
с `migrations/007_phone.sql`. На новом томе выполняются все семь миграций. Для более ранней схемы сначала
примените пропущенные миграции по порядку. Подробности и ограничения для исторических
сирот — в [инструкции обновления](docs/deploy.md).

## Android

```bash
cd android
./gradlew assembleDebug
# Для вашего сервера:
./gradlew assembleDebug -Pumbra.serverUrl=https://messenger.example.org
```

Debug по умолчанию использует 10.0.2.2:8080. HTTP разрешён только локальным адресам
в debug. Release требует явно заданный HTTPS URL и собственную подпись.

Обновляйте приложение с сохранением данных и тем же ключом подписи. Перерегистрация
не заменяет существующую identity. Выход сохраняет ключи и историю. Перенос ключей
на другое устройство пока не реализован. Подробнее — [android/README.md](android/README.md).

## Основные настройки

| Переменная | По умолчанию | Назначение |
|---|---|---|
| STORE | memory | memory или postgres |
| DATABASE_URL | пусто | Обязателен при postgres |
| TOKEN_TTL_SECONDS | 86400 | Срок действия токена |
| MAX_MESSAGE_BYTES | 2097152 | Лимит JSON-запроса сообщения |
| BLOB_STORE_TYPE | file | file или s3 |
| BLOB_DIR | ./data/blobs | Каталог зашифрованных файлов |
| MAX_MEDIA_BYTES | 52428800 | Лимит одного ciphertext-файла |
| MAX_USER_MEDIA_BYTES | 0 | Суммарная квота пользователя; Compose задаёт 1 GiB |
| S3_ENDPOINT / S3_ACCESS_KEY / S3_SECRET_KEY / S3_BUCKET / S3_REGION / S3_USE_SSL | см. .env.example | Параметры S3 |

Квота проверяется атомарно, включая параллельные загрузки. На экземпляр допускается
до восьми загрузок одновременно. Служба удаления обрабатывает очередь файлов
удалённых аккаунтов с повтором при недоступности хранилища.

GC требует PostgreSQL и те же БД/файлы, что у сервера. Он пропускает объекты моложе
24 часов и по умолчанию ничего не удаляет:

```bash
STORE=postgres go run ./cmd/gc
# После просмотра результата:
STORE=postgres go run ./cmd/gc -delete
```

## Контракт и ограничения приватности

[API](docs/api.md) описывает регистрацию, ключи, сообщения, файлы, группы,
сигналинг и аккаунт. Бинарные поля JSON передаются в base64. Новые Android-клиенты
используют key_version=2: подпись pre-key делается ключом Signal, а Ed25519 служит
для входа. Прежние Ed25519 raw/X.509 и legacy-аккаунты поддерживаются сервером.

Сервер не получает приватные E2E-ключи. Имена пользователей, участники, адресация,
временные метки и размеры сообщений видны серверу. Нужен HTTPS. Первая identity
собеседника принимается по TOFU, последующая замена блокируется; UI сверки
fingerprints ещё не реализован.

Для файлов клиент обязан сам выполнить шифрование и передать ключ, nonce, имя
и реальный MIME внутри E2E-сообщения. Сервер не определяет, действительно ли
зашифрованы присланные байты. Любой авторизованный пользователь со случайным id
файла может скачать его ciphertext по текущему контракту API.

Удаление аккаунта убирает его серверные данные и ставит принадлежащие ему файлы
в очередь удаления. История других авторов в чужих группах сохраняется. Чаты,
созданные удаляемым пользователем, удаляются целиком. Это не удаляет копии,
уже сохранённые собеседниками, резервные копии и прежние версии S3.

## Проверки

```bash
go build ./...
go vet ./...
go test -race -count=1 ./...
```

Для PostgreSQL используйте отдельную тестовую БД:

```bash
TEST_DATABASE_URL='postgres://test_user:test_password@127.0.0.1:5432/test_db?sslmode=disable' go test -race ./internal/store
```

Тест создаёт отдельную схему, применяет миграции и удаляет только свою схему.
Для S3 задайте TEST_S3_ENDPOINT, TEST_S3_ACCESS_KEY, TEST_S3_SECRET_KEY,
TEST_S3_BUCKET и при необходимости TEST_S3_USE_SSL=true. Тест создаёт и удаляет
только собственный объект с префиксом umbra-test.

```bash
cd android
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
./gradlew connectedDebugAndroidTest
```

Android-тесты используют libsignal, Room, Android Keystore и MockWebServer.
Проверяются перезапуск, откат криптотранзакции, повтор отправки после потери
подтверждения, направление DM и миграция базы. Для их запуска нужен эмулятор.

Существующую проверку файлового API можно запустить на отдельном сервере с
MAX_MEDIA_BYTES=1024 командой `python3 scripts/smoke-media.py --limit 1024`.

## Лицензия

[GNU Affero General Public License v3.0](LICENSE).
