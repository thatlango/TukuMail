# ADR-007 — Tuku Engine v2

**Status:** Accepted for side-by-side pilot  
**Decision date:** 2026-09-11

## Context

TukuMail currently uses Apache James for standards-heavy SMTP/IMAP/mailbox execution. The TukuMail control plane already owns organisations, domains, entitlements, mailbox lifecycle and client APIs.

The strategic requirement is to remove James as a permanent architectural dependency without forcing Android, web, desktop, ImpactOS or organisation administrators through a second migration.

## Decision

Build Tuku Engine v2 as a separate Rust service behind the existing `MailEngine` interface.

James and Engine v2 must coexist until protocol and operations parity is demonstrated.

### Tuku-owned v2 boundaries

Engine v2 owns:

- mailbox credential verification;
- domains and aliases used by the mail execution layer;
- mailbox content/storage;
- local SMTP delivery;
- SMTP submission;
- IMAP;
- outbound queue and retry state;
- transport adapters;
- quota enforcement;
- future DKIM/SPF/DMARC and abuse controls.

The existing TukuMail control plane remains authoritative for:

- organisations;
- plans/subscriptions;
- entitlements;
- domain ownership verification;
- staff references;
- admin UX;
- user product sessions;
- audit/billing/integrations.

## Migration rule

No Tuku estate product may depend on James-specific APIs.

```text
ImpactOS / clients
        |
        v
TukuMail API
        |
     MailEngine
      /     \
   James    v2
```

A cutover is configuration, not an application rewrite.

## v2.0 acceptance gate

Before public protocol cutover:

- all existing Java/API tests green;
- Engine v2 fmt/test/clippy green;
- local delivery parity;
- authentication parity;
- mailbox listing/read parity;
- remote queue parity;
- native TLS;
- DKIM/SPF/DMARC;
- migration reconciliation with zero missing messages;
- 72-hour shadow/soak run;
- backup/restore proof;
- third-party client compatibility matrix.

## Rollback

During pilot, set `TUKUMAIL_ENGINE_PROVIDER=james`. James remains authoritative and retains current public protocol ports until a separately approved cutover.
