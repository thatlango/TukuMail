package org.tukutuku.mail.api.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="mail_domains")
public class MailDomain {
    @Id public UUID id;
    @Column(name="organization_id", nullable=false) public UUID organizationId;
    @Column(nullable=false, unique=true) public String name;
    @Column(nullable=false) public boolean verified;
    protected MailDomain() {}
    public MailDomain(UUID organizationId, String name, boolean verified) { this.id=UUID.randomUUID(); this.organizationId=organizationId; this.name=name; this.verified=verified; }
}
