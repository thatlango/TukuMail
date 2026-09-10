package org.tukutuku.mail.api.repo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tukutuku.mail.api.domain.MailDomain;
import java.util.*;
public interface MailDomainRepository extends JpaRepository<MailDomain, UUID> {
    Optional<MailDomain> findByNameIgnoreCase(String name);
    List<MailDomain> findByOrganizationId(UUID organizationId);
}
