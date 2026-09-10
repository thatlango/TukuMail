package org.tukutuku.mail.api.repo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.tukutuku.mail.api.domain.Organization;
import java.util.UUID;
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {}
