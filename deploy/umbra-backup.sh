#!/bin/bash
# Ежедневный бэкап БД Umbra: pg_dump -Fc, хранение 7 дней.
# Установка: cp deploy/umbra-backup.sh /usr/local/bin/
#            chown root:postgres /usr/local/bin/umbra-backup.sh
#            chmod 750 /usr/local/bin/umbra-backup.sh
#            mkdir -p /var/backups/umbra && chown postgres:postgres /var/backups/umbra
# Запуск: umbra-backup.timer (systemd) от пользователя postgres.
set -euo pipefail
D=/var/backups/umbra
STAMP=$(date +%F_%H%M)
pg_dump -Fc umbra > "$D/umbra_$STAMP.dump"
find "$D" -name '*.dump' -mtime +7 -delete
echo "$(date -Is) backup umbra_$STAMP.dump $(du -h "$D/umbra_$STAMP.dump" | cut -f1)"
