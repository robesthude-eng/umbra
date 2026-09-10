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
- **TURN не поднят.** Для звонков вне одной сети нужен coturn (и открытые порты
  3478 и диапазон relay). Пока клиент использует только STUN.
- **FCM не настроен.** Без `google-services.json` сборка APK идёт без push.
- **Лимит nginx** `client_max_body_size 64m` при `MAX_USER_MEDIA_BYTES=268435456`
  (256 МиБ) в `.env`: вложение крупнее 64 МБ отклонит nginx.
