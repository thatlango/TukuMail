#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${TUKUMAIL_ENV_FILE:-$ROOT/infrastructure/.env.prod}"
BACKUP_DIR="${TUKUMAIL_BACKUP_DIR:-/srv/tukumail/backups}"
RETENTION_DAYS="${TUKUMAIL_BACKUP_RETENTION_DAYS:-14}"

if [[ ! -r "$ENV_FILE" ]]; then
  echo "Missing production env file: $ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

mkdir -p "$BACKUP_DIR"
chmod 750 "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="$BACKUP_DIR/tukumail-postgres-$STAMP.sql.gz"

cd "$ROOT"
docker compose --env-file "$ENV_FILE" -f infrastructure/docker-compose.prod.yml exec -T postgres \
  pg_dumpall --username "${POSTGRES_USER:-james}" | gzip -9 > "$OUT"
chmod 640 "$OUT"

find "$BACKUP_DIR" -type f -name 'tukumail-postgres-*.sql.gz' -mtime "+$RETENTION_DAYS" -delete

if [[ -n "${TUKUMAIL_BACKUP_REMOTE:-}" ]]; then
  rsync -az -- "$OUT" "$TUKUMAIL_BACKUP_REMOTE"
fi

echo "Backup complete: $OUT"
