package org.tukutuku.mail.api.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tukutuku.mail.api.domain.*;
import org.tukutuku.mail.api.repo.*;
import org.tukutuku.mail.engine.*;

import java.security.SecureRandom;
import java.util.*;

@Service
public class ProvisioningService {
    private final OrganizationRepository organizations; private final MailDomainRepository domains; private final MailboxRepository mailboxes; private final MailEngine engine;
    private static final SecureRandom RANDOM = new SecureRandom();
    public ProvisioningService(OrganizationRepository organizations, MailDomainRepository domains, MailboxRepository mailboxes, MailEngine engine) { this.organizations=organizations; this.domains=domains; this.mailboxes=mailboxes; this.engine=engine; }

    @Transactional public Organization createOrganization(String name, String slug, String plan, int mailboxLimit, int storageLimitGb) {
        return organizations.save(new Organization(name, slug, plan, mailboxLimit, storageLimitGb));
    }
    @Transactional public MailDomain addDomain(UUID orgId, String rawDomain, boolean verified) {
        requireOrg(orgId); String domain=rawDomain.trim().toLowerCase(Locale.ROOT);
        if (!domain.matches("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) throw new IllegalArgumentException("Invalid domain");
        if (verified) engine.ensureDomain(domain);
        return domains.save(new MailDomain(orgId, domain, verified));
    }
    @Transactional public CreatedMailbox createMailbox(UUID orgId, String localPart, String domainName, String displayName, int quotaGb, String staffRef) {
        Organization org=requireOrg(orgId);
        long active=mailboxes.countByOrganizationIdAndStatus(orgId, "ACTIVE");
        if (active >= org.mailboxLimit) throw new IllegalStateException("Mailbox entitlement exhausted");
        MailDomain domain=domains.findByNameIgnoreCase(domainName).orElseThrow(() -> new IllegalArgumentException("Domain not registered"));
        if (!domain.organizationId.equals(orgId) || !domain.verified) throw new IllegalStateException("Domain is not verified for this organisation");
        String local=localPart.trim().toLowerCase(Locale.ROOT);
        if (!local.matches("[a-z0-9][a-z0-9._+-]{0,63}")) throw new IllegalArgumentException("Invalid mailbox name");
        String address=local+"@"+domain.name;
        if(mailboxes.findByAddressIgnoreCase(address).isPresent()) throw new IllegalStateException("Mailbox already exists");
        char[] password=temporaryPassword();
        engine.createMailbox(new MailboxProvision(address, displayName, quotaGb*1024L*1024L*1024L), password);
        Mailbox saved=mailboxes.save(new Mailbox(orgId,address,displayName,quotaGb,staffRef));
        return new CreatedMailbox(saved, new String(password));
    }
    @Transactional public Mailbox suspend(UUID mailboxId) {
        Mailbox m=mailboxes.findById(mailboxId).orElseThrow(); engine.suspendMailbox(m.address); m.status="SUSPENDED"; return mailboxes.save(m);
    }
    public List<Mailbox> list(UUID orgId) { requireOrg(orgId); return mailboxes.findByOrganizationIdOrderByDisplayNameAsc(orgId); }
    private Organization requireOrg(UUID id) { return organizations.findById(id).filter(o->o.active).orElseThrow(() -> new IllegalArgumentException("Organisation not found")); }
    private char[] temporaryPassword() { byte[] b=new byte[18]; RANDOM.nextBytes(b); return Base64.getUrlEncoder().withoutPadding().encodeToString(b).toCharArray(); }
    public record CreatedMailbox(Mailbox mailbox, String temporaryPassword) {}
}
