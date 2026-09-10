#!/usr/bin/env bash
set -euo pipefail

HOSTNAME="${TUKUMAIL_HOSTNAME:-mail.tukutuku.org}"
WEB_PORT="${TUKUMAIL_WEB_PORT:-8082}"

curl -fsS "http://127.0.0.1:${WEB_PORT}/api/v1/health" >/dev/null

echo | openssl s_client -quiet -connect 127.0.0.1:993 -servername "$HOSTNAME" 2>/dev/null | head -n 1 >/dev/null

echo | openssl s_client -quiet -starttls smtp -connect 127.0.0.1:25 -servername "$HOSTNAME" 2>/dev/null | head -n 1 >/dev/null

DISK_USE=$(df -P / | awk 'NR==2 {gsub("%", "", $5); print $5}')
if (( DISK_USE >= 85 )); then
  echo "Root disk usage is ${DISK_USE}%" >&2
  exit 2
fi

echo "TukuMail health checks passed (disk ${DISK_USE}%)."
