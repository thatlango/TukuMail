# SEI-01 — Sovereign email infrastructure
Date: 2026-11-25. Status: recovery / discovery, not production-ready.

## Product and architecture
TukuMail owns employee mailboxes and domains. Connex is the estate's provider-neutral transactional communication boundary. Tuku Core owns identity, service credentials and tenant authorisation. No estate application should need a third-party email credential. Keep the transactional and employee sending queues and IP reputation separate. Adopt self-hosted Postal as a candidate for transactional delivery, not an approved deployment. Keep James and the experimental Rust engine until mailbox parity and migration are proven. No live DNS, secrets, mail routes or customer data changes in this tranche.

## Recovery evidence
- Main f46d99a349188688a122c6497124313153b6f82f; engine v2 3fb4bb9426a46873bbbbcccfea54d887e957a776: 100 ahead, 20 behind. Candidate2 adds only a CI marker. v0 is an ancestor of v2.
- Historical successful run 34607703575 on candidate2; re-run on reconciled baseline.
- Existing main foundation and TDS 2.0 changes must survive recovery. Resolve conflicts deliberately; do not force-push or merge solely on historical green CI.
- OVH host agent available; Docker socket access denied. /srv/tukumail-staging listing returned zero visible entries. Host socket listing showed no SMTP/IMAP listeners. This is not proof that no mail containers exist.
- Confirmed direct dependency: Tuku-Core src/modules/invitations/impactos-invitation-email.service.ts uses api.resend.com and RESEND_API_KEY. Global code search returned zero despite this known match; cannot treat it as a complete audit. No standalone Connex repo discovered by repository name; determine authoritative implementation.
- Existing GO_LIVE.md requires dedicated mail VPS. Do not repurpose the application production host as public MTA.

## Work packages
1. Recovery: compare main vs v2, merge conflicts on integration branch, preserve TDS 2.0 and all working features. Run Java, Rust, web, Android, desktop, configuration and protocol tests. Evidence: green CI on exact integrated SHA.
2. Estate inventory: locate Connex owner/repo, inventory direct Resend SDK, HTTP endpoints and environment references by path across app repos, including auth provider and operational notifications. Do not collect secret values. Track migration owner and tests per caller.
3. Pilot host: separately provision dedicated IP/host only after approval. Confirm outbound TCP 25, forward/reverse DNS, TLS, resources, provider terms, reputation and licensing. Use scripts/sovereign_mail_preflight.py from intended mail host with --hostname, --public-ip and repeat --mx. This checks DNS and SMTP greeting only; not authentication or deliverability. Run unit tests: python3 -m unittest discover -s scripts -p 'test_sovereign_mail_preflight.py'.
4. Transport spike: pin Postal release, dependencies and image digests after official installation and license review. Private admin/DB; no public open relay. Authenticated API, tenant/domain verification, limits, signing, inbound bounce endpoint and signed callback reconciliation. Test in isolated environment with synthetic recipients only.
5. Connex contract: durable enqueue with tenant-scoped idempotency; canonical message ID; delivery status separate from acceptance; scoped credentials; approved sender domain; no implicit fallback after ambiguous provider acceptance; signed/replay-safe webhook handling. Store message bodies/tokens minimally and never log invitation links or credentials. Transport-neutral SDK. Keep existing Resend route as temporary explicit legacy option until controlled migration.
6. Cutover gates: cross-provider delivery headers and inbox/spam evidence, bounce/suppression, retries, duplicate prevention, rate/abuse controls, restore drill, failover without ambiguous re-dispatch, monitoring, rollback, operator approval. Migrate low-risk notifications before auth/finance. Remove Resend only after all known callers and secrets have been migrated, independent service qualified and rollback documented.

## Current release decision
No production cutover. Draft PR for recovery and preflight, not a green release claim. Record exact verification results and gaps as work proceeds.
