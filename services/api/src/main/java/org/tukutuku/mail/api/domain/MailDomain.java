package org.tukutuku.mail.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="mail_domains")
public class MailDomain {
    @Id public UUID id;
    @Column(name="organization_id", nullable=false) public UUID organizationId;
    @Column(nullable=false, unique=true) public String name;
    @Column(nullable=false) public boolean verified;
    @Column(name="verification_token", nullable=false, length=128) public String verificationToken;
    @Column(name="verified_at") public Instant verifiedAt;
    protected MailDomain() {}
    public MailDomain(UUID organizationId, String name, String verificationToken) {
        this.id=UUID.randomUUID();
        this.organizationId=organizationId;
        this.name=name;
        this.verified=false;
        this.verificationToken=verificationToken;
    }
}
