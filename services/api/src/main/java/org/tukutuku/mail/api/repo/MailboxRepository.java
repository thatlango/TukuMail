package org.tukutuku.mail.api.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tukutuku.mail.api.domain.Mailbox;

import java.util.*;

public interface MailboxRepository extends JpaRepository<Mailbox, UUID> {
    Optional<Mailbox> findByAddressIgnoreCase(String address);
    List<Mailbox> findByOrganizationIdOrderByDisplayNameAsc(UUID organizationId);
    long countByOrganizationIdAndStatus(UUID organizationId, String status);

    @Query("select coalesce(sum(m.quotaGb), 0) from Mailbox m where m.organizationId = :organizationId and m.status = 'ACTIVE'")
    long activeQuotaGb(@Param("organizationId") UUID organizationId);
}
