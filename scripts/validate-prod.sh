#!/usr/bin/env bash
set -euo pipefail

export TUKUMAIL_HOSTNAME="${TUKUMAIL_HOSTNAME:-mail.example.test}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-ci-not-a-secret}"
export TUKUMAIL_PROVISIONING_KEY="${TUKUMAIL_PROVISIONING_KEY:-ci-provisioning-key}"\nexport TUKUMAIL_ENGINE_V2_KEY="${TUKUMAIL_ENGINE_V2_KEY:-ci-engine-v2-key-at-least-24-chars}"
export TUKUMAIL_TLS_DIR="${TUKUMAIL_TLS_DIR:-/tmp/tukumail-ci-tls}"

python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for path in [
    Path('infrastructure/james/smtpserver.prod.xml'),
    Path('infrastructure/james/imapserver.prod.xml'),
]:
    ET.parse(path)
    print(f'XML OK: {path}')
PY

docker compose --env-file infrastructure/.env.prod.example -f infrastructure/docker-compose.prod.yml config >/dev/null
docker build -q -f apps/web/Dockerfile . >/dev/null

echo "Production configuration OK"
