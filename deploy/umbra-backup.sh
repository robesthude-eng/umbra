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
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'umbra_*.umbraenc' -mtime +7 -delete
printf 'Backup completed: %s\n' "${final##*/}"
