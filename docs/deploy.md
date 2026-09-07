# Деплой Umbra: reverse-proxy, TLS, hardening

Инструкция по развёртыванию сервера Umbra на production VPS. Исходит из того, что у вас
есть сервер с Docker Compose (стек из `docker-compose.yml`).

> ⚠️ **Выбор хостинга.** Для цели проекта (приватность, стойкость к принудительному
> доступу) размещайте сервер в юрисдикции **без** обязательного перехвата данных.
> Тестовый стенд можно держать где угодно; на релиз — переезжайте за пределы РФ.

## 1. Подготовка VPS

```bash
# Обновить систему (пример для Debian/Ubuntu)
apt update && apt upgrade -y

# Установить Docker
curl -fsSL https://get.docker.com | sh

# Создать пользователя для деплоя, НЕ работать под root
adduser deploy
usermod -aG docker deploy
```

## 2. Развёртывание приложения

```bash
# от пользователя deploy
git clone https://github.com/robesthude-eng/umbra.git
cd umbra/server
cp .env.example .env
# отредактировать .env: POSTGRES_PASSWORD, при желании BLOB_DIR, MAX_MEDIA_BYTES
openssl rand -hex 32   # сгенерировать пароль БД
docker compose up -d --build
```

Проверка:

```bash
curl http://localhost:8080/healthz   # {"status":"ok"}
```

## 3. TLS-терминация: Caddy

Файл `deploy/Caddyfile` уже подготовлен. Caddy автоматически выпускает Let's Encrypt
сертификат и проксирует на внутренний порт 8080.

```bash
# Установить Caddy (Debian/Ubuntu)
apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | tee /etc/apt/sources.list.d/caddy-stable.list
apt update && apt install caddy -y

# Положить конфиг
cp deploy/Caddyfile /etc/caddy/Caddyfile
# Заменить ДОМЕН в файле
systemctl reload caddy
```

### Замечание по WebSocket

Caddy проксирует WebSocket-апгрейд автоматически (он reverse-proxy по умолчанию). Убедитесь,
что `/v1/ws` не обрывается на таймауте — в Caddyfile задан разумный read/write timeout.

## 4. Systemd-юнит (альтернатива docker compose)

Если запускаете бинарник напрямую (без Docker), используйте `deploy/umbra.service`:

```bash
go build -o /usr/local/bin/umbra-server ./cmd/server
cp deploy/umbra.service /etc/systemd/system/
# отредактировать Environment= в юните под свои значения
systemctl daemon-reload
systemctl enable --now umbra
```

## 5. Hardening

```bash
# 1. Файрвол — открыть только 22 (SSH), 80, 443
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable

# 2. Отключить вход root по паролю, только SSH-ключ
#    /etc/ssh/sshd_config:
#    PermitRootLogin prohibit-password
#    PasswordAuthentication no
systemctl reload ssh

# 3. fail2ban против брутфорса SSH
apt install fail2ban -y
systemctl enable --now fail2ban
```

## 6. Бэкапы и обслуживание (GC)

Критично бэкапить **и БД, и blob-директорию** вместе (иначе медиа осиротеют):

```bash
# БД
docker compose exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > backup.sql

# Блобы (по пути BLOB_DIR, напр. ./data/blobs)
tar czf blobs.tgz data/blobs
```

### 6.1 Автоматизация на systemd-хосте (без Docker)

В `deploy/` лежат готовые юниты и таймеры:

| Файл | Назначение |
|---|---|
| `umbra-backup.sh` | `pg_dump -Fc umbra` в `/var/backups/umbra`, ротация 7 дней |
| `umbra-backup.service` + `umbra-backup.timer` | ежедневный бэкап БД (~03:00, от `postgres`) |
| `umbra-gc.service` + `umbra-gc.timer` | ежедневный GC осиротевших блобов (~04:30) |

Установка:

```bash
cp deploy/umbra-backup.sh /usr/local/bin/
chown root:postgres /usr/local/bin/umbra-backup.sh
chmod 750 /usr/local/bin/umbra-backup.sh
mkdir -p /var/backups/umbra && chown postgres:postgres /var/backups/umbra

cp deploy/umbra-backup.service deploy/umbra-backup.timer \
   deploy/umbra-gc.service deploy/umbra-gc.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now umbra-backup.timer umbra-gc.timer

# Проверка: прогнать вручную и посмотреть таймеры
systemctl start umbra-backup.service umbra-gc.service
systemctl list-timers 'umbra*'
journalctl -u umbra-gc.service -n 20 --no-pager
```

### 6.2 GC и льготный период

Загрузка публикует blob в хранилище **раньше**, чем фиксирует строку метаданных,
поэтому GC по умолчанию не трогает сирот младше 24 часов (`-min-age=24h` в
`umbra-gc.service`) — иначе параллельный запуск мог бы удалить файл идущей
загрузки. Настоящие сироты (после сбоя или «сжигания» аккаунта) удаляются
следующим запуском. `-min-age=0` отключает льготный период.

GC требует `STORE=postgres` (тот же `EnvironmentFile`, что у `umbra.service`):
с in-memory хранилищем метаданных нет и все блобы выглядели бы сиротами —
запуск аварийно прекращается.

Бэкап blob-директории таймером не закрыт намеренно: блобы — шифротекст, их
копия на том же хосте не добавляет приватности. Для офсайт-копии синхронизируйте
`BLOB_DIR` (или S3-бакет) отдельно, например `restic`/`rclone` в cron.

## 7. Чек-лист релиза

- [ ] Хостинг вне РФ, без принудительного доступа
- [ ] TLS через Caddy (HTTPS работает, сертификат валиден)
- [ ] SSH только по ключу, root-вход по паролю отключён
- [ ] ufw: только 22/80/443
- [ ] fail2ban активен
- [ ] Пароль БД из .env, не дефолтный
- [ ] Бэкапы настроены (`umbra-backup.timer` активен, дампы появляются)
- [ ] GC настроен (`umbra-gc.timer` активен, `-min-age` ≥ 1h)
- [ ] `curl https://ДОМЕН/healthz` → `{"status":"ok"}`
