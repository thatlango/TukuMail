package org.tukutuku.mail.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tukutuku.mail.api.domain.*;
import org.tukutuku.mail.api.repo.*;
import org.tukutuku.mail.engine.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class ProvisioningService {
    private final OrganizationRepository organizations;
    private final MailDomainRepository domains;
    private final MailboxRepository mailboxes;
    private final MailEngine engine;
    private final DnsVerificationService dnsVerification;
    private static final SecureRandom RANDOM = new SecureRandom();

    public ProvisioningService(OrganizationRepository organizations, MailDomainRepository domains, MailboxRepository mailboxes, MailEngine engine, DnsVerificationService dnsVerification) {
        this.organizations = organizations;
        this.domains = domains;
        this.mailboxes = mailboxes;
        this.engine = engine;
        this.dnsVerification = dnsVerification;
    }

    @Transactional
    public Organization createOrganization(String name, String slug, String plan, int mailboxLimit, int storageLimitGb) {
        return organizations.save(new Organization(name, slug, plan, mailboxLimit, storageLimitGb));
    }

    @Transactional
    public MailDomain addDomain(UUID orgId, String rawDomain) {
        requireOrg(orgId);
        String domain = normalizeDomain(rawDomain);
        if (domains.findByNameIgnoreCase(domain).isPresent()) throw new IllegalStateException("Domain already registered");
        return domains.save(new MailDomain(orgId, domain, randomToken()));
    }

    @Transactional
    public MailDomain verifyDomain(UUID orgId, UUID domainId) {
        requireOrg(orgId);
        MailDomain domain = domains.findById(domainId).orElseThrow(() -> new IllegalArgumentException("Domain not found"));
        if (!domain.organizationId.equals(orgId)) throw new IllegalArgumentException("Domain not found");
        if (domain.verified) return domain;
        if (!dnsVerification.verifies(domain.name, domain.verificationToken)) {
            throw new IllegalStateException("DNS verification record not found");
        }
        engine.ensureDomain(domain.name);
        domain.verified = true;
        domain.verifiedAt = Instant.now();
        return domains.save(domain);
    }

    public List<MailDomain> listDomains(UUID orgId) {
        requireOrg(orgId);
        return domains.findByOrganizationId(orgId);
    }

    @Transactional
    public CreatedMailbox createMailbox(UUID orgId, String localPart, String domainName, String displayName, int quotaGb, String staffRef) {
        Organization org = requireOrg(orgId);
        long active = mailboxes.countByOrganizationIdAndStatus(orgId, "ACTIVE");
        if (active >= org.mailboxLimit) throw new IllegalStateException("Mailbox entitlement exhausted");
        long allocatedGb = mailboxes.activeQuotaGb(orgId);
        if (allocatedGb + quotaGb > org.storageLimitGb) throw new IllegalStateException("Storage entitlement exhausted");

        MailDomain domain = domains.findByNameIgnoreCase(domainName)
            .orElseThrow(() -> new IllegalArgumentException("Domain not registered"));
        if (!domain.organizationId.equals(orgId) || !domain.verified) {
            throw new IllegalStateException("Domain is not verified for this organisation");
        }

        String local = localPart.trim().toLowerCase(Locale.ROOT);
        if (!local.matches("[a-z0-9][a-z0-9._+-]{0,63}")) throw new IllegalArgumentException("Invalid mailbox name");
        String address = local + "@" + domain.name;
        if (mailboxes.findByAddressIgnoreCase(address).isPresent()) throw new IllegalStateException("Mailbox already exists");

        char[] password = temporaryPassword();
        try {
            engine.createMailbox(new MailboxProvision(address, displayName, quotaGb * 1024L * 1024L * 1024L), password);
            Mailbox saved = mailboxes.save(new Mailbox(orgId, address, displayName, quotaGb, staffRef));
            return new CreatedMailbox(saved, new String(password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Transactional
    public Mailbox suspend(UUID mailboxId) {
        Mailbox mailbox = mailboxes.findById(mailboxId).orElseThrow();
        engine.suspendMailbox(mailbox.address);
        mailbox.status = "SUSPENDED";
        return mailboxes.save(mailbox);
    }

    public List<Mailbox> list(UUID orgId) {
        requireOrg(orgId);
        return mailboxes.findByOrganizationIdOrderByDisplayNameAsc(orgId);
    }

    private Organization requireOrg(UUID id) {
        return organizations.findById(id).filter(o -> o.active)
            .orElseThrow(() -> new IllegalArgumentException("Organisation not found"));
    }

    private static String normalizeDomain(String rawDomain) {
        String domain = rawDomain.trim().toLowerCase(Locale.ROOT);
        if (!domain.matches("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) {
            throw new IllegalArgumentException("Invalid domain");
        }
        return domain;
    }

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private char[] temporaryPassword() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }

    public record CreatedMailbox(Mailbox mailbox, String temporaryPassword) {}
}
