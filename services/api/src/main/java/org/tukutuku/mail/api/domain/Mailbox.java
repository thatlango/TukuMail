package org.tukutuku.mail.api.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="mailboxes")
public class Mailbox {
    @Id public UUID id;
    @Column(name="organization_id", nullable=false) public UUID organizationId;
    @Column(nullable=false, unique=true) public String address;
    @Column(name="display_name", nullable=false) public String displayName;
    @Column(name="quota_gb", nullable=false) public int quotaGb;
    @Column(nullable=false) public String status;
    @Column(name="staff_ref") public String staffRef;
    @Column(name="created_at", nullable=false) public Instant createdAt;
    protected Mailbox() {}
    public Mailbox(UUID organizationId, String address, String displayName, int quotaGb, String staffRef) {
        this.id=UUID.randomUUID(); this.organizationId=organizationId; this.address=address; this.displayName=displayName; this.quotaGb=quotaGb; this.staffRef=staffRef; this.status="ACTIVE"; this.createdAt=Instant.now();
    }
}
