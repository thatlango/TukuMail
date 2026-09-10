# TukuMail

TukuMail is the Tuku-owned business email platform. This monorepo contains the control-plane backend, mail-engine integration, web client, Android client, desktop client, shared contracts, infrastructure, and ImpactOS/JakeOS integrations.

## Product boundaries

Tuku owns organisations, domains, subscriptions, entitlements, provisioning, mailbox lifecycle, aliases, quotas, audit, billing, UX and monitoring. The mail engine owns standards-heavy SMTP/IMAP transport, mailbox protocol behaviour and delivery. Apache James components are used behind a Tuku-owned engine boundary and can be progressively replaced without changing client or ImpactOS contracts.

## Workspace

- `services/api` — TukuMail control-plane API
- `services/engine` — Tuku Mail Engine facade and James integration boundary
- `apps/web` — browser mail client
- `apps/android` — Android client
- `apps/desktop` — desktop shell
- `packages/contracts` — shared OpenAPI/domain contracts
- `infrastructure` — deployment, DNS, backup and observability
- `integrations/impactos` — staff lifecycle provisioning contract

Brand assets are placeholders by design and can be replaced later without changing application structure.
