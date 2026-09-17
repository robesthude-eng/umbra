#!/usr/bin/env bash
# Проверка каталога migrations/: нумерация без дублей и пропусков,
# имя по формату NNN_name.sql, файл не пустой и атомарен (BEGIN/COMMIT),
# если явно не помечен как неатомарный комментарием "-- non-atomic".
#
# Требование BEGIN/COMMIT действует с миграции ATOMIC_FROM: более старые
# файлы уже накачены на бою, переписывать их опаснее, чем оставить как есть.
#
# Причина: миграции накатываются по алфавиту (glob), и дубль номера
# или незакрытая транзакция ловятся сейчас только на боевом прогоне.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dir="$root/migrations"
status=0
prev=0
ATOMIC_FROM=${ATOMIC_FROM:-16}

shopt -s nullglob
files=("$dir"/*.sql)
if [ ${#files[@]} -eq 0 ]; then
  echo "migrations/: нет ни одного .sql" >&2
  exit 1
fi

for path in "${files[@]}"; do
  name="$(basename "$path")"

  if [[ ! "$name" =~ ^([0-9]{3})_[a-z0-9_]+\.sql$ ]]; then
    echo "$name: ожидается имя вида NNN_snake_case.sql" >&2
    status=1
    continue
  fi
  num=$((10#${BASH_REMATCH[1]}))

  if [ "$num" -eq "$prev" ]; then
    echo "$name: дублирующийся номер миграции $num" >&2
    status=1
  elif [ "$num" -ne $((prev + 1)) ]; then
    echo "$name: пропущены номера между $prev и $num" >&2
    status=1
  fi
  prev=$num

  if [ ! -s "$path" ]; then
    echo "$name: пустой файл" >&2
    status=1
    continue
  fi

  if [ "$num" -lt "$ATOMIC_FROM" ]; then
    continue
  fi

  if grep -qi -- '-- non-atomic' "$path"; then
    continue
  fi

  if ! grep -qiE '^[[:space:]]*BEGIN[[:space:]]*;' "$path"; then
    echo "$name: нет BEGIN; (или пометьте файл комментарием -- non-atomic)" >&2
    status=1
  fi
  if ! grep -qiE '^[[:space:]]*COMMIT[[:space:]]*;' "$path"; then
    echo "$name: нет COMMIT;" >&2
    status=1
  fi
done

if [ "$status" -eq 0 ]; then
  echo "migrations/: проверено файлов: ${#files[@]}"
fi
exit "$status"
