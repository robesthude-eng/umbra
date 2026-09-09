# Android v0.4 — переделка под модель T1 (код из Telegram, облачные сообщения)

Дата: 2026-09-08. Решения пользователя: OTP-вход по номеру с кодом из Telegram
(код приходит владельцу в личный чат бота, с любого номера — без привязок);
один аккаунт на номер; вся история в облаке; профиль (аватар-кружок, @ник,
имя обязательно, фамилия опционально); нижний таб-бар Чаты/Группы/Звонки/
Настройки; стиль Liquid Glass без фиолетового; старый E2EE (Signal) убираем.

## Состояние окружения (полезно при продолжении)
- Локальная сборка Android: JDK21 (`/usr/lib/jvm/java-21-openjdk-amd64`),
  Android SDK `/home/android-sdk` (platforms;android-35, build-tools;34.0.0,
  platform-tools), Gradle 8.9 (wrapper). Сборка: `cd android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/android-sdk ./gradlew :app:compileDebugKotlin` (GRADLE_USER_HOME стоит держать вне workspace, напр. /home/gradle, чтобы не раздувать снапшоты).
- Go 1.27.1: `/home/user/.cache/go-toolchain/go/bin/go`; модуль-кэш `/home/user/.cache/gomod`.
- Пуш в git: конфиг `.git/config` (remote+токен) между сообщениями теряется — использовать `bash /home/user/work/gitpush.sh`.

## Сетевой адрес API (для APK)
Выбран https-домен: `https://api.agentwill.ru`. ВАЖНО: DNS зоны agentwill.ru
обслуживается у рег.ру (NS = ns1.reg.ru), НЕ в Cloudflare (в CF только Worker +
учётка, но не DNS). Запись A `api → 194.226.126.253` пользователь должен добавить
в DNS-панели регистратора (рег.ру). Nginx на VPS уже готов: сайт `umbra`
слушает :80 c server_name `api.agentwill.ru`/`umbra.agentwill.ru`, проксирует на
127.0.0.1:8080. Как только DNS заработает: `certbot --nginx -d api.agentwill.ru`
и проверить `https://api.agentwill.ru/healthz`. Временный запасной вариант,
который уже работает: `https://game.agentwill.ru:8443` (тот же API, свой
сертификат) — для тестов, не для продукта.
Менять конфиги web-game/chassis-3d нельзя; сайт `umbra` в sites-enabled — наш.

## Контракт сервера (проверено по коду internal/httpapi)
- OTP: `POST /v1/auth/request_code {phone}`; `POST /v1/auth/verify_code {phone,code}`
  → `{token, expires_at, new_account, profile_complete, account{id,username,phone,display_name,last_name}}`.
- Профиль: `POST /v1/account/profile {name, last_name?, username?}`;
  `POST /v1/account/avatar {media_id}`; `GET /v1/account` → + `avatar_media_id`.
- `GET /v1/users/{id}` (НОВОЕ, есть на проде после деплоя) → карточка
  `{id,username,display_name,last_name,avatar_media_id,created_at}`.
- Сообщения: ciphertext-поле — это **base64** от содержимого (сервер делает
  b64-decode/encode). Для T1 содержимое = plaintext JSON-конверт (см. ниже).
  DM: `POST /v1/messages {recipient_id,ciphertext,client_id,expires_in}`.
  Группа: `POST /v1/chats/{id}/messages {ciphertext,...}`; создание
  `POST /v1/groups {title}` (создатель авто-owner); члены: `POST/GET/DELETE
  /v1/chats/{id}/members`, `GET /v1/chats/{id}/members` (только user_id+role).
  `GET /v1/messages?since=&after_id=&limit=` возвращает входящие+исходящие DM и
  сообщения групп, где пользователь участник (chat_id заполнен) — этого хватает
  для докачки истории с нуля и инкрементальной синхронизации.
- Медиа: `POST /v1/media` multipart (file + content_type) → `{id,...}`;
  `GET /v1/media/{id}` — любой авторизованный. Аватар = обычное медиа.
- Звонки (WebRTC-сигналинг): `POST /v1/calls {callee_id,video}`,
  `POST /v1/calls/{id}/signal {to,kind(offer|answer|ice),payload}`,
  `POST /v1/calls/{id}/status {active|ended|declined|missed}`, `GET /v1/calls`.
  События приходят по WS: type `call` (data=callResponse), `call_signal`
  {call_id,from,kind,payload}, `call_status` {call_id,status}, `message`.
- WS: `GET /v1/ws?token=...` (или Bearer).

## Новый клиент — устройство (все файлы под android/app/src/main)
1. **data/api/UmbraApi.kt** — переписать: убрать RegisterRequest/Verify/PreKey;
   добавить RequestCode/VerifyCode, AccountView(+last_name/avatar), UserCard,
   ChatDto(тип+title+created_by), MemberDto(user_id,role,display_name? нет —
   имена через GET /v1/users/{id}), сообщения как есть (ciphertext=base64
   конверта). Эндпоинты users/get users, account/profile/avatar, media, groups.
2. **MessageBody (конверт, plaintext)**: `{"v":1,"kind":"text|media","text":...,
   "media_id","name","mime","size"}` — кладём в JSON → UTF-8 → base64 → ciphertext.
   Парсим на приёме. Медиа качаем через /v1/media/{id} (сырой файл, без шифрования).
3. **Сессия**: выкинуть CryptoManager/Signal. Новый лёгкий SessionStore
   (EncryptedSharedPreferences через androidx.security-crypto, что уже в проекте):
   token, userId, username, phone, displayName, lastName, avatarMediaId.
   При verify_code сохраняем сессию + данные аккаунта.
4. **Room**: схему можно не менять (version 3). Семантика:
   - `MessageEntity.ciphertext` = base64-конверт с сервера (или локальный для outbox),
   - `localBody` = открытый текст конверта (для кэша/офлайна), БЕЗ AEAD-печати.
   - При первом OTP-входе чистить локальные messages/chats/contacts (старый
     E2EE-мусор не читаем) — clearAllTables после верификации, перед синком.
   - ВАЖНО: для установленного v0.3 на телефоне схема та же (версия 3),
     поэтому апдейт пройдёт без миграции.
5. **ChatRepository (T1)**: удалить register/login/loginLegacy (ключевые),
   сигнальные вызовы, prepareAccount(keys), sync contacts оставить (полезно),
   но discover не обязателен: имена дёргать через GET /v1/users/{id}.
   Новые методы:
   - `requestCode(phone)` → POST /v1/auth/request_code.
   - `verifyCode(phone, code)` → POST verify_code; сохранить сессию; вернуть
     результат (newAccount/profileComplete). Если profileComplete=false —
     UI показывает экран профиля.
   - `updateProfile(name, lastName, username)` / `setAvatar(mediaId)`.
   - `syncNow()`: GET /v1/messages пагинация по since/after_id (как сейчас),
     persist(): для DM chatId = собеседник, для group — chat_id; localBody =
     декодированный текст конверта; заголовки чатов резолвить из UserCard кэша.
   - `chats()` — Flow из DB + вычисляемая карточка (последнее сообщение/дата);
     для простоты: отдельная View-таблица `chat_previews` не нужна — вычисляем
     в UI из messagesFor группы? Лучше: хранить по каждому chatId в DAO query
     последнее сообщение. Сделать DAO: lastMessagePerChat.
   - outbox: pending c client_id, flush при сети, как сейчас, но без crypto.
   - Медиа: sendMedia upload raw (content_type настоящий!), конверт kind=media,
     fetchMediaFile просто скачивает файл в кэш (проверка размера из конверта).
   - Группы: createGroup(title), addMember(userId), removeMember, listMembers
     (+разрез UserCard), sendGroupMessage через /v1/chats/{id}/messages;
     в persist для group тоже создаём/обновляем ChatEntity.
   - Звонки: держать в отдельном CallRepository/ViewModel? Проще методы в
     ChatRepository: callPeer(id), accept/decline/end, sendSignal, list calls;
     события WS — в сторадж через flow. Вкладка Звонки: история + исходящий/
     входящий экран.
6. **WebSocketClient**: оставить, приспособить URL wss://.../v1/ws?token=...;
   поток событий в репозиторий (message/call/call_signal/call_status).
7. **UI**:
   - AuthScreen (номер → код → профиль). Профиль: кружок-аватар сверху с
     выбором фото (PhotoPicker/SAF), @никнейм, Имя (обязательно), Фамилия
     (опц.), кнопка «Продолжить». Путь входа: если profileComplete — сразу в чаты
     с докачкой истории (sync EPOCH).
   - Главный экран с нижним таб-баром: Чаты / Группы / Звонки / Настройки
     (шестерёнка). Внутри: списки, экран чата (переиспользовать ChatScreen
     облегчённо, без E2E-бейджей/замков), группа, настройки (профиль, аватар,
     выход, удалить аккаунт).
   - Тема: Theme.kt — Liquid Glass: полупрозрачный фон, blur (Android 12+
     blur behind можно скромно), стеклянные карточки, акцент голубой/синий,
     БЕЗ фиолетового. Иконки tabs: Chat/Forum/Call/Settings.
   - Звонки: вкладка со списком (GET /v1/calls) и входящий звонок (WS event)
     — accept/decline; медиа-часть WebRTC — отдельным этапом (сервер даёт
     только сигналинг, TURN нет; обсудить позже).
8. **Сборка/подпись**: versionCode 4→5, versionName 0.4.0; URL через
   `-Pumbra.serverUrl=https://api.agentwill.ru`. Стабильный ключ — из секретов
   GitHub (CI собирает подписанный APK артефактом); локально только compileDebug.

## Порядок работ
1. Сервер: GET /v1/users/{id} — готово (коммит e347ae0), задеплоено на прод (маршрут жив, 401 без токена).
2. DNS api.agentwill.ru у рег.ру + certbot (ждём запись от пользователя).
3. Android: data layer (контракт, конверт, сессия, репо T1, выпил Signal).
4. Android: UI auth+профиль, таб-бар, чаты/группы, тема Liquid Glass.
5. Локальный compileDebugKotlin до зелёного, push → CI (соберёт APK), ручная
   проверка на телефоне с реальным кодом из Telegram.

## Статус (2026-09-08, сессия после компиляции)
- Сервер: единый `GET /v1/by-username/{username}` (внешний 6fed67e) — это
  эталон; локальный дубль `/v1/resolve` НЕ переносим. go build + go test
  internal/httpapi PASS. Прод-деплой нового бинарника НЕ выполнялся в этой
  сессии — проверить актуальность бинарника на VPS.
- Android: data+UI T1 написаны и **compileDebugKotlin зелёный**
  (commit 6db8d10 + фиксы компиляции 6739adc, origin/main). Что сделано:
  SessionStore, конверт-сообщения (MessageContent/MessageCodec, поле ciphertext
  = base64 JSON), инкрементальная синхронизация по maxCreatedAtMillis,
  AuthScreen (номер→код→профиль), UmbraRoot по фазам, MainShell с таб-баром
  Чаты/Группы/Звонки/Настройки, ChatView, журнал/оверлей звонка (сигналинг),
  Liquid Glass-тема. Room: schema version 3, добавлены maxCreatedAtMillis/all.
- Осталось: убрать libsignal-зависимости из gradle (код не использует; чистка
  сборки); деплой сервера; APK: versionCode 4→5, versionName 0.4.0,
  -Pumbra.serverUrl=https://api.agentwill.ru, стабильный ключ; проверка на
  телефоне (реальный код из Telegram-бота владельцу).
- Локальный тулчейн (не переносится между сессиями): JDK21 в /usr/lib/jvm,
  SDK в /home/user/.cache/android-sdk, gradle-home в /home/user/.cache/gradle-home,
  при сборке требуется swap (2GB) из-за 2GB RAM: mkswap+swapon на
  /home/user/.cache/swapfile.

## Статус (2026-09-08, вечер — деплой и CI)
- Сервер ДЕПЛОИТСЯ на VPS (root@194.226.126.253): /usr/local/bin/umbra-server
  заменён на собранный из main (sha256 9c83d7…), service umbra active.
  GET /v1/by-username/{x} теперь 401 (маршрут жив), /v1/account 401.
  Миграции не нужны (users.username UNIQUE с 001). Резервная копия бинарника —
  /root/umbra-deploy-bak/umbra-server.pre-byusername.*.
- Правило пользователя: APK собирает ТОЛЬКО GitHub Actions; локально JDK не ставить.
  Workflow .github/workflows/android-apk.yml: подписанный debug-APK на push в main
  (стабильный ключ из секретов UMBRA_SIGNING_*). Для f2fd8ad — SUCCESS,
  артефакт umbra-apk-debug ~18 МБ (Actions → Artifacts, 60 дней).
- versionCode=5, versionName=0.4.0; URL по умолчанию http://194.226.126.253:8081
  (HTTP-стенд; cleartext к этому IP разрешён в debug network_security_config).
- checks.yml: сервер go build/vet/test PASS; android-job: компиляция+lint (эмуляторы
  убраны). Линт правится через collectAsState вместо StateFlow.value в композиции.
- Из зависимостей удалены libsignal-client/android/bouncycastle (Signal выпилен),
  androidTest E2EE-тесты удалены.

## Статус (2026-09-09) — продакшен на 0.4.1
- Пользователь подтвердил: APK 0.4.1 (versionCode 6, run 34314176645) на
  телефоне работает нормально.
- Сервер на VPS обновлён из main 20d0b69: /usr/local/bin/umbra-server
  sha256 35789c8c… (старый 9c83d7…). Бэкап:
  /root/umbra-deploy-bak/umbra-server.pre-0.4.1.20260909-085857.
  service umbra active; PostgreSQL, файловые блобы, Telegram-бот OTP включён.
  Маршруты /v1/account, /v1/by-username/*, /v1/chats отвечают 401 (живы).
- Миграции БД НЕ требовались: message_receipts уже создана миграцией 006;
  снапшот не менял migrations/.

## Статус (2026-09-09) — прод на 0.4.2 (миграция 011 применена)
- Интегрирован внешний снапшот 0.4.2 (1fc647d: версия политики входа,
  TRUSTED_PROXIES, продление сессий TTL 30 дней, versionCode 8) и фикс
  «имя обязательно всегда» + gofmt (7f99e64). CI зелёный.
- Прод: бэкап БД umbra-pre0.4.2.20260909-132503.dump и бинарника
  umbra-server.pre-0.4.2.20260909-132503; миграция 011 применена один раз
  (удалён 1 токен; account_transfers 0). .env: TOKEN_TTL_SECONDS=2592000,
  ALLOW_LEGACY_AUTH=false, TRUSTED_PROXIES=127.0.0.1,::1. Новый бинарник
  sha256 dc91d0e6… active.
- ВАЖНО: после 011 все сессии отозваны — нужен ОДИН повторный вход по коду
  из Telegram (аккаунты/переписка/медиа сохранены).
- Актуальный APK 0.4.2 (versionCode 8): Actions run 34336661781 (1fc647d).
