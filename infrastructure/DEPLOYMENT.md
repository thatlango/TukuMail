# VPS deployment sequence

1. Provision a dedicated mail VPS with a static IPv4 address. Do not colocate it with the main Tuku application/database VPS.
2. Set the hostname and PTR/reverse DNS to the final mail hostname.
3. Create persistent mail-data and backup locations.
4. Build the TukuMail API and web client from tagged source.
5. Configure a non-demo Apache James JPA/Postgres-backed runtime and real TLS material.
6. Keep James WebAdmin private; only the TukuMail API may reach it.
7. Configure customer-domain MX/SPF/DKIM/DMARC records and verify them before mailbox activation.
8. Run end-to-end delivery tests against at least Gmail and Outlook before onboarding customers.
9. Enable nightly off-server backups and a restore drill.
10. Add VPS, SMTP/IMAP reachability, queue, disk, certificate and blacklist/reputation checks to JakeOS.

Do not deploy the `demo-3.9.0` development container to production.
