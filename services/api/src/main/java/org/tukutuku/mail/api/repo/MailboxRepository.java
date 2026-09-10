package org.tukutuku.mail.api.repo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tukutuku.mail.api.domain.Mailbox;
import java.util.*;
public interface MailboxRepository extends JpaRepository<Mailbox, UUID> {
    Optional<Mailbox> findByAddressIgnoreCase(String address);
    List<Mailbox> findByOrganizationIdOrderByDisplayNameAsc(UUID organizationId);
    long countByOrganizationIdAndStatus(UUID organizationId, String status);
}
