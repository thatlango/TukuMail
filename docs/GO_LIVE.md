# TukuMail go-live handoff

This is the boundary between repository work and external infrastructure actions.

## What is already in the repository

- TukuMail control-plane API and replaceable mail-engine boundary.
- Native Android client, responsive web client and Tauri desktop client.
- Production Compose stack using one PostgreSQL service plus pinned `apache/james:postgres-3.9.0`.
- Database and James WebAdmin are private to the Compose network.
- SMTP 25, SMTPS 465, submission 587 and IMAPS 993 are the only mail ports published.
- SMTP/IMAP use a real PEM certificate copied into `/srv/tukumail/tls`.
- DNS TXT ownership challenge before a domain can become active.
- Mailbox-count and storage entitlement enforcement.
- Backup, health-check, TLS-sync and CI scripts.

## External actions required from the operator

### 1. Provision the dedicated mail VPS

Do not install the mail engine on the existing Tuku production application VPS. Use a dedicated Linux VPS with:

- static public IPv4
- x86_64 Ubuntu 24.04 LTS or equivalent
- 4 vCPU recommended
- 8 GB RAM recommended
- 100 GB+ SSD/NVMe to start

Set its hostname to `mail.tukutuku.org` (or change `TUKUMAIL_HOSTNAME` consistently before deployment).

### 2. Set the two DNS records needed before TLS

At the DNS provider:

```text
mail.tukutuku.org. A <NEW_MAIL_VPS_IPV4>
```

At the VPS provider, set reverse DNS/PTR:

```text
<NEW_MAIL_VPS_IPV4> -> mail.tukutuku.org
```

Forward A and reverse PTR must agree.

### 3. Allow inbound ports

Provider firewall and host firewall must allow TCP:

```text
25   SMTP between mail servers
80   ACME/web redirect
443  TukuMail web HTTPS
465  authenticated SMTPS
587  authenticated SMTP submission
993  IMAPS
```

Do not publish PostgreSQL 5432 or James WebAdmin 8000.

### 4. Bootstrap web TLS

Install Docker Engine/Compose, Nginx and Certbot on the dedicated VPS. Clone TukuMail, copy `infrastructure/nginx/tukumail.conf.example` to the host Nginx configuration, enable it and obtain a Let's Encrypt certificate for the mail hostname.

After Certbot succeeds:

```bash
sudo TUKUMAIL_HOSTNAME=mail.tukutuku.org bash scripts/sync-mail-tls.sh
```

Configure the same command as a Certbot deploy hook so renewed certificates are copied into `/srv/tukumail/tls`; restart `mail-engine` after a renewal.

### 5. Create production secrets

```bash
cp infrastructure/.env.prod.example infrastructure/.env.prod
```

Replace at minimum:

- `POSTGRES_PASSWORD`
- `TUKUMAIL_PROVISIONING_KEY`

Generate them locally, for example with `openssl rand -base64 48`. Never commit `.env.prod`.

### 6. Start TukuMail

```bash
docker compose --env-file infrastructure/.env.prod \
  -f infrastructure/docker-compose.prod.yml up -d --build

TUKUMAIL_HOSTNAME=mail.tukutuku.org bash scripts/healthcheck-prod.sh
```

### 7. Register the pilot domain

Use the TukuMail provisioning API to create the Tuku organisation and register `tukutuku.org`. The response returns `verificationToken`.

Publish:

```text
_tukumail-verification.tukutuku.org TXT "tukumail-verification=<verificationToken>"
```

Then call the domain verification endpoint. Only successful DNS resolution activates the domain in the mail engine.

### 8. Publish mail delivery DNS

After the domain is verified, publish:

```text
tukutuku.org.        MX 10  mail.tukutuku.org.
tukutuku.org.        TXT    "v=spf1 mx -all"
_dmarc.tukutuku.org. TXT    "v=DMARC1; p=quarantine; rua=mailto:dmarc@tukutuku.org"
```

DKIM must be configured and its public TXT record published before external production sending is enabled. Multi-domain DKIM key management remains a release gate; do not onboard paying external domains without it.

### 9. Prove delivery before customer onboarding

Create pilot mailboxes and test both directions against Gmail and Outlook:

- TukuMail -> Gmail
- Gmail -> TukuMail
- TukuMail -> Outlook
- Outlook -> TukuMail
- replies and multi-message threads
- attachments once attachment support is promoted into the client release

Check SPF/DKIM/DMARC results in received-message headers and check spam placement.

### 10. Backups and distribution signing

Schedule `scripts/backup-prod.sh` nightly and set `TUKUMAIL_BACKUP_REMOTE` to a separate machine/storage target when available. Run and document one restore drill before customer data is trusted to the service.

For public distribution, supply:

- Android release keystore / Play Console signing decision
- Apple Developer signing/notarization credentials for macOS
- Windows code-signing certificate if distributing a trusted Windows installer
- final TukuMail logos/icons when branding is ready

## Go-live gate

Do not sell or migrate customer mail until all of these are true: dedicated IP, matching PTR/A, CA-issued TLS, DNS ownership verification, MX/SPF/DKIM/DMARC, external send/receive tests, off-server backup and restore test, and signed client builds where applicable.
