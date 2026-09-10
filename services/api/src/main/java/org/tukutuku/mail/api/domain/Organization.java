package org.tukutuku.mail.api.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="organizations")
public class Organization {
    @Id public UUID id;
    @Column(nullable=false) public String name;
    @Column(nullable=false, unique=true) public String slug;
    @Column(nullable=false) public String plan;
    @Column(name="mailbox_limit", nullable=false) public int mailboxLimit;
    @Column(name="storage_limit_gb", nullable=false) public int storageLimitGb;
    @Column(nullable=false) public boolean active;
    protected Organization() {}
    public Organization(String name, String slug, String plan, int mailboxLimit, int storageLimitGb) {
        this.id=UUID.randomUUID(); this.name=name; this.slug=slug; this.plan=plan; this.mailboxLimit=mailboxLimit; this.storageLimitGb=storageLimitGb; this.active=true;
    }
}
