# Журнал выкаток сервера Umbra

Сервер: `194.226.126.253`, systemd-юнит `umbra`, бинарник `/usr/local/bin/umbra-server`,
данные в `/opt/umbra` (PostgreSQL 16, блобы в `/opt/umbra/data/blobs`), `.env` — `/opt/umbra/.env`.
Внешний доступ: `http://194.226.126.253:8081` (nginx) и `https://game.agentwill.ru:8443`.

Автоматического журнала миграций нет, поэтому применённые миграции фиксируются здесь.

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
