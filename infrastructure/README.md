# TukuMail infrastructure

## Development

The development stack intentionally uses Apache James' `demo-3.9.0` image because it is a reproducible single-node SMTP/IMAP test environment. It is **not** a production image: it includes demo identities and defaults.

```bash
docker compose -f infrastructure/docker-compose.dev.yml up --build
```

Run the web client separately with `npm install && npm run web:dev`, or point Android debug builds at the API through the emulator's `10.0.2.2` host mapping.

## Production boundary

Production should use the Apache James JPA or Postgres-backed distribution with a Tuku-managed configuration, real certificates and persistent backups. WebAdmin must remain private to the TukuMail API network; never expose it to the public Internet with authentication disabled.

Public service ports are SMTP 25, submission 587/465 as configured, and IMAPS 993. HTTP(S) serves the TukuMail API/web client through the VPS edge proxy already used by the Tuku estate.

See `DNS.md`, `SECURITY.md` and `DEPLOYMENT.md` before exposing mail publicly.
