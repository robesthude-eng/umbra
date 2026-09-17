#!/bin/bash
# verify-restore.sh - учения по восстановлению: берёт самый свежий шифрованный дамп,
# раскрывает его, восстанавливает в одноразовую базу и проверяет структуру.
#
# Без этого бэкап - не бэкап, а файл. Скрипт ничего не пишет в продакшн-базу:
# целевая БД создаётся и удаляется сама.
#
# Запуск:
#   sudo -u deploy env $(grep -v '^#' /opt/umbra/backup.env | xargs) bash scripts/verify-restore.sh
set -euo pipefail
umask 077

BACKUP_DIR=${BACKUP_DIR:-/var/backups/umbra}
STORAGE_KEY_FILE=${STORAGE_KEY_FILE:-/opt/umbra/data/storage-keys.json}
UMBRA_STORAGE_BIN=${UMBRA_STORAGE_BIN:-/usr/local/bin/umbra-storage}
VERIFY_DB=${VERIFY_DB:-umbra_restore_check}
MIGRATIONS_DIR=${MIGRATIONS_DIR:-migrations}
export PGHOST=${PGHOST:-127.0.0.1} PGPORT=${PGPORT:-5432}
export PGDATABASE=${PGDATABASE:-umbra} PGUSER=${PGUSER:-umbra}
: "${PGPASSFILE:?Set PGPASSFILE to an owned PostgreSQL password file}"
export PGPASSFILE

latest=$(find "$BACKUP_DIR" -maxdepth 1 -type f -name 'umbra_*.umbraenc' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)
if [[ -z "${latest:-}" ]]; then
  echo "Нет ни одного бэкапа в $BACKUP_DIR" >&2
  exit 1
fi
echo "Проверяем: ${latest##*/}"

age_days=$(( ( $(date +%s) - $(stat -c %Y "$latest") ) / 86400 ))
if (( age_days > 2 )); then
  echo "Самый свежий бэкап старше двух суток ($age_days дн.) - таймер не работает?" >&2
  exit 1
fi

work=$(mktemp -d)
cleanup() {
  rm -rf -- "$work"
  dropdb --if-exists --no-password "$VERIFY_DB" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

plain="$work/dump.pgcustom"
"$UMBRA_STORAGE_BIN" decrypt-backup -key-file "$STORAGE_KEY_FILE" < "$latest" > "$plain"
[[ -s "$plain" ]] || { echo 'Расшифрованный дамп пуст' >&2; exit 1; }

# pg_restore --list сразу покажет битый файл, не трогая БД.
pg_restore --list "$plain" > "$work/toc.txt"
echo "Объектов в дампе: $(grep -c '^[0-9]' "$work/toc.txt")"

dropdb --if-exists --no-password "$VERIFY_DB"
createdb --no-password "$VERIFY_DB"
pg_restore --no-password --dbname "$VERIFY_DB" --no-owner --no-privileges --exit-on-error "$plain"

# Контрольные проверки: схема на месте и в ней есть данные.
required_tables=(users sessions messages)
for t in "${required_tables[@]}"; do
  if ! psql --no-password -d "$VERIFY_DB" -tAc "select to_regclass('public.$t') is not null" | grep -q '^t$'; then
    echo "В восстановленной БД нет таблицы $t" >&2
    exit 1
  fi
done

users=$(psql --no-password -d "$VERIFY_DB" -tAc 'select count(*) from users')
echo "Восстановлено пользователей: $users"

# Если в репо миграций больше, чем применено в дампе, бэкап старее деплоя.
if psql --no-password -d "$VERIFY_DB" -tAc "select to_regclass('public.schema_migrations') is not null" | grep -q '^t$'; then
  applied=$(psql --no-password -d "$VERIFY_DB" -tAc 'select count(*) from schema_migrations')
  if [[ -d "$MIGRATIONS_DIR" ]]; then
    ondisk=$(find "$MIGRATIONS_DIR" -maxdepth 1 -name '*.sql' | wc -l)
    echo "Миграции: в дампе $applied, в репо $ondisk"
    if (( applied < ondisk )); then
      echo 'ВНИМАНИЕ: бэкап сделан до применения части миграций' >&2
    fi
  fi
fi

# Кейринг хранится отдельно от дампа, и без него данные не расшифровать.
if [[ ! -s "$STORAGE_KEY_FILE" ]]; then
  echo "Кейринг $STORAGE_KEY_FILE пуст или отсутствует - восстановление бессмысленно" >&2
  exit 1
fi

echo 'Учения пройдены: дамп расшифровывается и восстанавливается.'
