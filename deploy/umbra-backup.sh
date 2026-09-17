#!/bin/bash
# Encrypted pg_dump; installation and environment: SECURITY.md.
set -euo pipefail
umask 077
BACKUP_DIR=${BACKUP_DIR:-/var/backups/umbra}
STORAGE_KEY_FILE=${STORAGE_KEY_FILE:-/opt/umbra/data/storage-keys.json}
UMBRA_STORAGE_BIN=${UMBRA_STORAGE_BIN:-/usr/local/bin/umbra-storage}
export PGHOST=${PGHOST:-127.0.0.1} PGPORT=${PGPORT:-5432}
export PGDATABASE=${PGDATABASE:-umbra} PGUSER=${PGUSER:-umbra}
: "${PGPASSFILE:?Set PGPASSFILE to an owned PostgreSQL password file}"
export PGPASSFILE
[[ -f "$PGPASSFILE" && ! -L "$PGPASSFILE" && -O "$PGPASSFILE" ]] || { echo 'Invalid PGPASSFILE' >&2; exit 1; }
[[ $(stat -c %a "$PGPASSFILE") =~ ^[46]00$ ]] || { echo 'PGPASSFILE must have mode 0600 or 0400' >&2; exit 1; }
mkdir -p -m 700 "$BACKUP_DIR"
[[ -d "$BACKUP_DIR" && ! -L "$BACKUP_DIR" && -O "$BACKUP_DIR" && $(stat -c %a "$BACKUP_DIR") == 700 ]] || { echo 'Backup directory must be owned and mode 0700' >&2; exit 1; }
exec 9>"$BACKUP_DIR/backup.lock"
flock -n 9 || exit 0
part=''
cleanup() { if [[ -n "$part" ]]; then rm -f -- "$part"; fi; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
part=$(mktemp "$BACKUP_DIR/umbra_$(date -u +%Y%m%dT%H%M%SZ).XXXXXX.part")
pg_dump --no-password --format=custom | "$UMBRA_STORAGE_BIN" encrypt-backup -key-file "$STORAGE_KEY_FILE" > "$part"
sync -f "$part"
final=${part%.part}.umbraenc
mv -- "$part" "$final"
part=''
sync -f "$BACKUP_DIR"
# Кейринг копируется рядом: без него дамп невозможно расшифровать.
# Копию обязательно уносить на другой носитель (см. docs/deploy.md).
if [[ -s "$STORAGE_KEY_FILE" ]]; then
	install -m 600 "$STORAGE_KEY_FILE" "$BACKUP_DIR/keyring_$(date -u +%Y%m%d).json"
	find "$BACKUP_DIR" -maxdepth 1 -type f -name 'keyring_*.json' -mtime +30 -delete
else
	echo "WARNING: keyring $STORAGE_KEY_FILE is missing; dumps will not be decryptable" >&2
fi

# Ретеншн: 7 ежедневных + 4 недельных (воскресный дамп помечается жёсткой ссылкой).
if [[ $(date -u +%u) == 7 ]]; then
	ln -f -- "$final" "${final%.umbraenc}.weekly.umbraenc"
fi
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'umbra_*.umbraenc' ! -name '*.weekly.umbraenc' -mtime +7 -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.weekly.umbraenc' -mtime +28 -delete
sync -f "$BACKUP_DIR"
printf 'Backup completed: %s\n' "${final##*/}"
printf 'Kept: %s daily, %s weekly\n' \
	"$(find "$BACKUP_DIR" -maxdepth 1 -name 'umbra_*.umbraenc' ! -name '*.weekly.umbraenc' | wc -l)" \
	"$(find "$BACKUP_DIR" -maxdepth 1 -name '*.weekly.umbraenc' | wc -l)"
