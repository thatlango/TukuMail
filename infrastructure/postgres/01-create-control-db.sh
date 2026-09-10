#!/usr/bin/env bash
set -euo pipefail

CONTROL_DATABASE="${CONTROL_DATABASE:-tukumail}"

if ! psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -tAc "SELECT 1 FROM pg_database WHERE datname='${CONTROL_DATABASE}'" | grep -q 1; then
  createdb --username "$POSTGRES_USER" "$CONTROL_DATABASE"
fi
