# ImpactOS integration

ImpactOS will provision TukuMail through the control-plane API, never by writing mail-engine storage directly.

Staff lifecycle events:
- staff onboarded -> request mailbox/aliases according to entitlement;
- name/role changed -> update display metadata and approved aliases;
- staff suspended -> suspend sign-in and outbound delivery according to policy;
- staff offboarded -> preserve/transfer mailbox according to retention policy before deletion.

Every action must be idempotent and auditable.
