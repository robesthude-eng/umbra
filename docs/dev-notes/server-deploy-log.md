# Журнал выкаток сервера Umbra

Сервер: `194.226.126.253`, systemd-юнит `umbra`, бинарник `/usr/local/bin/umbra-server`,
данные в `/opt/umbra` (PostgreSQL 16, блобы в `/opt/umbra/data/blobs`), `.env` — `/opt/umbra/.env`.
Внешний доступ: `http://194.226.126.253:8081` (nginx) и `https://game.agentwill.ru:8443`.

Автоматического журнала миграций нет, поэтому применённые миграции фиксируются здесь.

## 2026-09-11 — самообновление приложения (раздача /app/)

Клиент 0.15.0 (vc22) при запуске сверяет versionCode с `/app/latest.json`.
Серверная часть — только статика через nginx, Go-код не менялся.

| Параметр | Значение |
|---|---|
| Файлы | `/opt/umbra/app/latest.json` (версия+sha256+примечание) и `/opt/umbra/app/umbra-latest.apk` |
| nginx | `location = /app/latest.json` (json, no-cache) и `location /app/` (apk) добавлены во все три server-блока (80, 8081, 8443) |
| Бэкап vhost | `/root/umbra-deploy-bak/umbra.vhost.pre-app-update.<TS>` |
| Проверка | `latest.json` = 200 на 80/8081/8443 и снаружи по IP; APK = 200, `Content-Type: application/vnd.android.package-archive`, Content-Length совпадает |
| Текущая версия | versionCode 32 (0.16.9 — см. секцию выше), sha256 `2c745d4cafa3c1455743722016c009430ba6b6405a3103c7eb77f29268cf3817`; раздача обновлена 2026-09-11 18:16 MSK |

Порядок обновления при новых релизах: собрать APK в CI → скопировать в
`/opt/umbra/app/umbra-latest.apk` → перезаписать `latest.json` (versionCode,
versionName, sha256, notes). Клиенты при следующем запуске предложат обновление.

## 2026-09-11 — клиент 0.16.9 (vc32): снапшот 0.16.7–0.16.9 — Network Recovery, Motion, Alien Interface

Внешний снапшот (Drive), пачка трёх версий. Сетевые фиксы 0.16.5–0.16.6 подтверждены
в коде снапшота до интеграции: IsoTime-парсер (3 места, 0 сырых Instant.parse),
флаш outbox независимо от синхронизации, таймаут мьютекса 30 с + счётчик попыток,
DiagLog + wiring, инкрементальный refresh при переподключении, «Медленный режим».
Новое: монитор ConnectivityManager с пересозданием WS при смене маршрута,
jitter в backoff, встроенная проверка сети в настройках, Alien Interface (opt-in).

| Параметр | Значение |
|---|---|
| Коммиты | `6bf09f1` (снапшот), `1c0e9a0` (фикс импортов: ExperimentalActivityApi убран — PredictiveBackHandler стабилен; Color/RoundedCornerShape в SettingsTab) |
| CI | Оба воркфлоу зелёные на `1c0e9a0` |
| APK | sha256 `2c745d4cafa3c1455743722016c009430ba6b6405a3103c7eb77f29268cf3817`, 61 303 701 Б, versionCode 32 |
| latest.json | Путь `/app/umbra-latest.apk` (со слэшем — после инцидента с 0.16.6) |
| Текущая версия | versionCode 32 (0.16.9), раздача обновлена 2026-09-11 18:16 MSK |

## 2026-09-11 — хотфикс latest.json: путь к APK без ведущего слэша ломал скачивание 0.16.6

После выкладки 0.16.6 тест-телефон четыре раза проверил latest.json, но скачивание
падало мгновенно без единого запроса к APK: в json был `"apk": "umbra-latest.apk"`
(без `/`), а клиент строит URL как `base + apk` → `http://194.226.126.253:8081umbra-latest.apk`
— недопустимый адрес, IllegalArgumentException до HTTP. В json для vc28 путь был
корректный (`/app/umbra-latest.apk`), поэтому предыдущие обновления работали.

| Параметр | Значение |
|---|---|
| Фикс | `"apk": "/app/umbra-latest.apk"` в `/opt/umbra/app/latest.json`; бэкап `/root/umbra-deploy-bak/latest.json.pre-apk-path-fix.20260911` |
| Проверка | curl снаружи: json 200 с корректным путём; APK 206 на range-запрос |
| На будущее | В клиенте (план 0.16.7): терпимый path-join в AppUpdater.download + DiagLog ошибок обновления; в latest.json всегда путь от корня со слэшем |

## 2026-09-11 — клиент 0.16.6 (vc29): починка синхронизации/отправки после рестарта сервера в TZ Europe/Moscow

Инцидент «test-устройство принимает, но не отправляет»: 2026-09-10 ~09:36/20:44
на прод был накатан пересобранный серверный бинарий и сервис перезапущен в зоне
Europe/Moscow — pgx декодирует timestamptz в локальную зону процесса, поэтому
все метки из БД в `GET /v1/messages` получили смещение `+03:00`, а `Instant.parse`
на Android понимает только `Z`: синхронизация падала на первом сообщении,
а `flushOutbox` выполнялся только после успешной загрузки истории — очередь
отправки не уходила вовсе. Приём работал через живой WebSocket (метки там
из `time.Now().UTC()`, суффикс `Z`).

| Параметр | Значение |
|---|---|
| Клиент | 0.16.6 (vc29): толерантный парсер `IsoTime`, флаш очереди не зависит от синхронизации |
| Коммиты | `fa3bb82` (клиент), `79e0efb` (сервер: `.UTC()` на всех DB-метках — в прод пока НЕ деплоился) |
| APK | `/opt/umbra/app/umbra-latest.apk`, sha256 `6cffa3a25f860e6664e2f59671e42457e62f91ac73421aaaa184a5ef5e99286d`, 61 254 517 Б |
| Сервер | Решение владельца: TZ=UTC + рестарт. Выполнено 2026-09-11 16:53:08 MSK: drop-in `/etc/systemd/system/umbra.service.d/tz.conf` (`Environment=TZ=UTC`), `daemon-reload` + рестарт; бэкап юнита `/root/umbra-deploy-bak/umbra.service.pre-tzutc.<TS>`; проверено: TZ=UTC в `/proc/<pid>/environ`, `/healthz` ok, PostgreSQL/Telegram-бот подняты, логи процесса идут в UTC. Инкрементальный фикс `.UTC()` (79e0efb) остаётся в main на будущие выкатки |
| Примечание | Все серверные выкатки 8–10 сентября (v2, byusername, «0.9.1») выполнялись в сессиях arena.ai; текущий бинарий — сборка main (маршруты /v1/channels, /v1/by-username подтверждены), перезапуск 10.09 20:44 в зоне Europe/Moscow и включил баг «+03:00». На проде БД без миграции 012 (`push_devices`); 013 (`calls.participants`) применена |

## 0.9.1 — исправления звонков и медиа (фикс-пак)


| Параметр | Значение |
|---|---|
| Дата | 2026-09-10 |
| Коммит | `c33712a` (vc14, CI зелёный: checks + APK) |
| Бинарник | sha256 `3bb77be757879db84cd8308225903f0cfea475a03c1ce842b12041d9ae3ac88d` |
| Бинарник gc | sha256 `9fb35b6c6a458b0d869850e897f1e9615826de2cd440dfc6048020183ca8ade3` (обновлён заодно: прежний остался от старой сборки; dry-run чист) |
| Бэкап БД | `/root/umbra-deploy-bak/umbra-pre0.9.1.20260910-204351.dump` (pg_dump -Fc от postgres, 14 TABLE DATA) |
| Бэкапы | `umbra-server.pre-0.9.1.20260910-204351`, `umbra-gc.pre-0.9.1.20260910-204351`, `umbra.env.pre-0.9.1.20260910-204351` |
| Миграции | не требуются (новых нет); переменные окружения не менялись |
| Проверка | `/healthz` = 200 ×3, `/v1/turn` = 401, `/v1/calls` = 401, `/v1/media` = 405, `/v1/push/devices` = 405, через nginx `:8081` = 401; TURN по-прежнему выдаёт учётки |
| Данные | users=1, media=2, messages=0, chats=0, calls=0 (не изменились) |

Суть серверных правок 0.9.1: выход одного участника группового звонка не завершает
разговор в базе (`callLeaverStore` в памяти процесса, TTL 6 ч) — запись закрывается
только при отмене звонящим до ответа или когда остался ≤1 участник. Перезапуск
сервера посреди разговора возвращает старое поведение для текущих звонков (документировано
в `FIXES_SCREENSHOTS.md`), данные не теряются.

Откат: вернуть `umbra-server.pre-0.9.1.<TS>` и перезапустить юнит.

## 0.9.0 — вложения и групповые звонки

| Параметр | Значение |
|---|---|
| Дата | 2026-09-10 |
| Бинарник | sha256 `d818a9fe2ec6171ddb4013bb9b0cff1bd52d423d852de1f5821edbf9a86a5363` |
| Бэкап БД | `/root/umbra-deploy-bak/umbra-pre0.9.0.20260910-060940.dump` (pg_dump -Fc, 28 объектов) |
| Бэкап бинарника | `/root/umbra-deploy-bak/umbra-server.pre-0.9.0.20260910-060940` |
| Бэкап env | `/root/umbra-deploy-bak/umbra.env.pre-0.9.0.20260910-060940` |
| Миграция | `013_call_participants.sql` применена однократно (`ALTER TABLE` / `UPDATE 0` / `CREATE INDEX`) |
| Проверка | `/healthz` = 200, `/v1/turn` = 401, `/v1/calls` = 401, `/v1/push/devices` = 405 |
| Данные | users=1, media=2, messages=0, chats=0 (не изменились) |

Новых переменных окружения для 0.9.0 не требуется. TURN и FCM по-прежнему не
настроены: `/v1/turn` отвечает отказом без учётки, клиент использует STUN.

## 2026-09-10 — coturn (TURN) и лимит вложений nginx

Выполнено по решению владельца (пункты «поднять TURN» и «поднять лимит nginx»),
без изменения кода: сервер 0.9.0 и APK versionCode 13 уже поддерживают TURN
через `GET /v1/turn` (временные учётки TURN REST API).

### coturn (пакет `coturn` 4.6.1, юнит `coturn`)

| Параметр | Значение |
|---|---|
| Порты | слушает 3478/udp+tcp на 192.168.0.7; relay 49152–49251/udp+tcp |
| NAT | сервер за NAT хостера (внутр. 192.168.0.7 ↔ публ. 194.226.126.253), задан `external-ip=194.226.126.253` |
| Авторизация | `lt-cred-mech` + `use-auth-secret`, секрет в `TURN_SECRET` в `/opt/umbra/.env` (в репозиторий не выкладывать) |
| realm | `agentwill.ru` |
| ufw | добавлены правила `3478/tcp`, `3478/udp`, `49152:49251/udp`, `49152:49251/tcp` |
| `.env` umbra | `TURN_URL=turn:194.226.126.253:3478?transport=udp,turn:194.226.126.253:3478?transport=tcp`, `TURN_SECRET=<тот же секрет>`, `TURN_TTL_SECONDS=3600`; сервис перезапущен |
| Логи | `/var/log/turnserver.log` (simple-log), ротация `/etc/logrotate.d/turnserver` (weekly ×4, copytruncate) |
| Бэкапы | `/root/umbra-deploy-bak/turnserver.conf.orig-apt` (конфиг пакета), `umbra.env.pre-turn.20260910-093614` |

Особенности конфигурации: закрыты приватные диапазоны через `denied-peer-ip`,
но `allowed-peer-ip=192.168.0.7` обязателен — `external-ip` отражает адреса
peers во внутреннюю форму, и без этого исключения relay↔relay (двое звонящих
через один TURN) получает 403 Forbidden IP на CHANNEL_BIND. TLS/DTLS на TURN
не включены (сертификата нет; UDP/TCP 3478 достаточно для реле).

Проверено с внешнего адреса (не с сервера): аллокации по временной учётке
(REST-подпись совпадает с выдачей `/v1/turn`), STUN Binding по UDP,
TCP-подключение к 3478, обмен данными relay↔relay и relay(TCP)↔relay(UDP)
в обе стороны; peer-адреса клиент видит в публичной форме (hairpin за NAT
обрабатывается корректно). `GET /v1/turn` возвращает STUN Google + два TURN URL
с учёткой TTL 3600.

### nginx: лимит вложений

Во всех трёх блоках vhost `umbra` (80, 8081, 8443) `client_max_body_size`
поднят `64m → 256m` — теперь соответствует `MAX_USER_MEDIA_BYTES=268435456`
(256 МиБ). `nginx -t` ок, reload без простоя. Проверка: POST 70 МБ и 250 МБ
на `/v1/media` через `:8081` — HTTP 401 (до правки 70 МБ давал 413 от nginx;
401 = тело пропущено nginx до приложения, отказ по авторизации).


## 0.4.2 — безопасность сессий

| Параметр | Значение |
|---|---|
| Дата | 2026-09-09 |
| Бинарник | sha256 `dc91d0e67dc0c8b6c1444e985d8ac462dc23fd50e71107a7fb716d942a388131` |
| Бэкап БД | `/root/umbra-deploy-bak/umbra-pre0.4.2.20260909-132503.dump` |
| Бэкап бинарника | `/root/umbra-deploy-bak/umbra-server.pre-0.4.2.20260909-132503` |
| Миграция | `011_revoke_legacy_sessions.sql` применена однократно (отозван 1 токен) |
| Env | `TOKEN_TTL_SECONDS=2592000`, `ALLOW_LEGACY_AUTH=false`, `TRUSTED_PROXIES=127.0.0.1,::1` |

## Открытые вопросы по инфраструктуре

- **HTTPS на домене не работает для Umbra.** A-запись `api.agentwill.ru` ведёт на
  сервер, но vhost Umbra слушает только порт 80; на 443 запросы с этим SNI попадают
  в чужой vhost (`chassis.agentwill.ru ... _`) и получают HTML другого проекта.
  Рабочие адреса API: `http://194.226.126.253:8081` и `https://game.agentwill.ru:8443`.
  Чтобы включить HTTPS на домене, нужен сертификат для `api.agentwill.ru` и
  `listen 443` в vhost `umbra` (правки конфигов `web-game`/`chassis-3d` не нужны,
  но порт 443 общий — решение за владельцем сервера).
- **TURN поднят 2026-09-10** (coturn, см. раздел выше) — пункт закрыт.
- **FCM не настроен.** Без `google-services.json` сборка APK идёт без push.
- **Лимит nginx** `client_max_body_size` поднят до `256m` 2026-09-10 под квоту
  `MAX_USER_MEDIA_BYTES` (256 МиБ) — пункт закрыт.
