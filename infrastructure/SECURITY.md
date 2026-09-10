# Security baseline

TukuMail must not be treated as production-ready merely because SMTP and IMAP answer on the public ports.

Required before public launch:

- Real CA-issued TLS certificates for SMTP submission and IMAP.
- WebAdmin isolated on the private container/network interface; never directly Internet-exposed.
- Replace the v0 provisioning shared key with Tuku Core service-to-service identity and scoped organisation claims.
- Rate-limit authentication and outbound submission; enforce relay restrictions.
- DKIM signing, SPF validation, DMARC policy handling and DNS hygiene.
- Inbound abuse/spam/malware controls and attachment policy.
- Brute-force and credential-stuffing telemetry.
- Encrypted off-server backups plus tested restore procedure.
- Per-organisation quotas, retention and offboarding policy.
- Audit mailbox creation, suspension, password reset, alias and domain changes.
- Never log message bodies, passwords or authentication tokens.

The current web API keeps authenticated IMAP credentials only in volatile process memory for a short session. This avoids persisting mailbox passwords server-side, but a backend restart invalidates sessions. The long-term Tuku Core/mail-token design should replace this v0 mechanism.
