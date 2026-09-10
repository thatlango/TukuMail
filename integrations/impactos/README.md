# ImpactOS → TukuMail

ImpactOS never talks directly to Apache James. It calls the TukuMail provisioning API after confirming the organisation has a TukuMail entitlement.

## Staff onboarding

1. ImpactOS creates/updates the staff member.
2. The administrator selects **Create work email**.
3. ImpactOS checks the organisation's TukuMail subscription entitlement and mailbox limit.
4. ImpactOS calls `POST /api/v1/organizations/{organizationId}/mailboxes` with the stable ImpactOS staff ID in `staffRef`.
5. TukuMail provisions the mailbox and returns a one-time temporary password.
6. ImpactOS shows the address and one-time credential once; it must not retain the clear-text password in ordinary staff records.

Example request:

```json
{
  "localPart": "grace.atim",
  "domain": "organisation.org",
  "displayName": "Grace Atim",
  "quotaGb": 5,
  "staffRef": "impactos:staff_01J..."
}
```

## Offboarding

ImpactOS should request mailbox suspension, not deletion. Retention, forwarding, archive and eventual deletion are separate explicit policies. This protects organisational records and prevents accidental mail loss.

## Authentication hardening

v0 uses `X-Tuku-Provisioning-Key` for trusted server-to-server provisioning. Replace this with Tuku Core service credentials and scoped organisation/entitlement claims before exposing ImpactOS self-service provisioning to customers.
