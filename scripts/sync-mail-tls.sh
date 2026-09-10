#!/usr/bin/env bash
set -euo pipefail

HOSTNAME="${1:-${TUKUMAIL_HOSTNAME:-mail.tukutuku.org}}"
SOURCE_DIR="/etc/letsencrypt/live/${HOSTNAME}"
TARGET_DIR="${TUKUMAIL_TLS_DIR:-/srv/tukumail/tls}"

if [[ ! -r "${SOURCE_DIR}/fullchain.pem" || ! -r "${SOURCE_DIR}/privkey.pem" ]]; then
  echo "Certificate material for ${HOSTNAME} is not readable at ${SOURCE_DIR}" >&2
  exit 1
fi

install -d -m 0750 "$TARGET_DIR"
install -m 0644 "${SOURCE_DIR}/fullchain.pem" "${TARGET_DIR}/fullchain.pem"
install -m 0640 "${SOURCE_DIR}/privkey.pem" "${TARGET_DIR}/privkey.pem"

echo "TukuMail TLS material synced for ${HOSTNAME}."
