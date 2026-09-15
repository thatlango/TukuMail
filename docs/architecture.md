# TukuMail architecture

TukuMail separates the **control plane** from the **mail engine**.

The control plane owns tenants, domains, mailbox lifecycle, aliases, quotas, entitlements, audit, billing, integrations, UI and policy. The engine owns standards-heavy SMTP/IMAP protocol behaviour, message storage, queues, delivery, retries and anti-abuse hooks.

## Engine strategy

Apache James remains an acceptable first implementation behind the `MailEngine` interface because owning the product does not require rewriting SMTP/IMAP state machines on day one. The boundary is intentional: Tuku clients and ImpactOS depend on Tuku contracts, not James APIs. Engine components can therefore be replaced progressively.

## Non-negotiable production gates

Before real mail is accepted or sent:
- domain ownership verification;
- SPF, DKIM and DMARC automation;
- TLS for submission, IMAP and inter-server SMTP;
- rate limits, abuse controls, malware/attachment scanning hooks;
- durable queue and message-store backups;
- bounce/complaint handling;
- mailbox quota enforcement;
- immutable admin/audit events;
- disaster-recovery restore test;
- external deliverability test to major mailbox providers.

The current foundation intentionally reports the engine as `not_configured`.
