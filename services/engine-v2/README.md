# Tuku Engine v2

Tuku Engine v2 is the Tuku-owned mail execution layer being built to replace Apache James progressively without changing TukuMail's client or organisation contracts.

## v2.0 pilot scope

Implemented in this service:

- PostgreSQL-owned domains, mailboxes, aliases and message metadata/content.
- Argon2 password hashing.
- Quota enforcement at local delivery.
- Private management HTTP API.
- SMTP server with EHLO/HELO, AUTH PLAIN, MAIL FROM, RCPT TO, DATA, RSET, NOOP and QUIT.
- Relay protection: unauthenticated sessions may only deliver to local recipients.
- Authenticated sender-envelope anti-spoofing.
- Local mailbox delivery.
- Durable outbound queue with retry state.
- Optional authenticated STARTTLS relay transport.
- Minimal read-only IMAP4rev1 pilot surface: CAPABILITY, LOGIN, LIST, SELECT, STATUS, SEARCH, FETCH, UID FETCH, NOOP and LOGOUT.
- Docker image and CI test/lint/build gates.

## Deliberately not cut over yet

James remains the active internet-facing engine while v2 runs side-by-side. The following must be completed before v2 can replace James on ports 25/465/587/993:

1. native TLS/STARTTLS listeners and certificate reload;
2. complete SMTP submission semantics and limits;
3. RFC-complete IMAP behaviour required by TukuMail Android/desktop and third-party clients;
4. MIME/attachment persistence rather than the pilot text-body projection;
5. DKIM signing and SPF/DMARC verification inside v2;
6. direct-MX outbound transport, bounce/DSN generation and queue administration;
7. spam/abuse controls and per-IP/per-user rate limits;
8. mailbox migration/import and reconciliation tooling;
9. crash/restart, backup/restore, concurrency and soak tests.

## Engine selection

The TukuMail Java control plane keeps the stable `MailEngine` interface.

- `TUKUMAIL_ENGINE_PROVIDER=james` — current production/staging behaviour.
- `TUKUMAIL_ENGINE_PROVIDER=v2` — use Tuku Engine v2 through its private HTTP adapter.

Engine v2's management port is never published publicly. Pilot SMTP/IMAP ports are bound to loopback only by the supplied compose file.

## Runtime environment

Required:

- `ENGINE_DATABASE_URL`
- `ENGINE_ADMIN_KEY` (minimum 24 characters)

Optional:

- `ENGINE_HTTP_ADDR` (default `0.0.0.0:8088`)
- `ENGINE_SMTP_ADDR` (default `0.0.0.0:2525`)
- `ENGINE_IMAP_ADDR` (default `0.0.0.0:2143`)
- `ENGINE_RELAY_HOST`
- `ENGINE_RELAY_PORT` (default `587`)
- `ENGINE_RELAY_USERNAME`
- `ENGINE_RELAY_PASSWORD`
- `RUST_LOG`

If no relay is configured, remote mail remains in the durable outbound queue. Local mail continues to work.

## Security boundary

Only the TukuMail control plane receives the Engine v2 admin key. ImpactOS, JakeOS, Android, web and desktop do not call Engine v2 directly.
