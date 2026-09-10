package org.tukutuku.mail.api.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.tukutuku.mail.api.domain.*;
import org.tukutuku.mail.api.service.*;
import org.tukutuku.mail.engine.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "${tukumail.cors-origin:http://localhost:5173}")
public class ApiController {
    private final ProvisioningService provisioning; private final SessionService sessions; private final MailEngine engine; private final String provisioningKey;
    public ApiController(ProvisioningService provisioning, SessionService sessions, MailEngine engine, @Value("${tukumail.provisioning-key:dev-only-change-me}") String provisioningKey) { this.provisioning=provisioning; this.sessions=sessions; this.engine=engine; this.provisioningKey=provisioningKey; }

    @GetMapping("/health") public Map<String,Object> health(){ return Map.of("product","TukuMail","status","ok","time", Instant.now()); }
    @PostMapping("/auth/session") public SessionResponse login(@Valid @RequestBody LoginRequest r) { return new SessionResponse(sessions.login(r.email(), r.password().toCharArray()), r.email().toLowerCase(Locale.ROOT)); }
    @DeleteMapping("/auth/session") public ResponseEntity<Void> logout(@RequestHeader("Authorization") String auth) { sessions.logout(token(auth)); return ResponseEntity.noContent().build(); }

    @GetMapping("/mail/inbox") public List<MessageSummary> inbox(@RequestHeader("Authorization") String auth,@RequestParam(defaultValue="50") @Min(1) @Max(100) int limit){ var c=sessions.require(token(auth)); return engine.inbox(c.address(),c.password(),limit); }
    @GetMapping("/mail/messages/{id}") public MessageDetail message(@RequestHeader("Authorization") String auth,@PathVariable String id){ var c=sessions.require(token(auth)); return engine.message(c.address(),c.password(),id); }
    @PostMapping("/mail/send") public ResponseEntity<Void> send(@RequestHeader("Authorization") String auth,@Valid @RequestBody SendRequest r){ var c=sessions.require(token(auth)); engine.send(c.address(),c.password(),new OutgoingMessage(r.to(),r.cc(),r.subject(),r.body())); return ResponseEntity.accepted().build(); }

    @PostMapping("/organizations") public Organization createOrg(@RequestHeader("X-Tuku-Provisioning-Key") String key,@Valid @RequestBody OrgRequest r){ requireKey(key); return provisioning.createOrganization(r.name(),r.slug(),r.plan(),r.mailboxLimit(),r.storageLimitGb()); }
    @PostMapping("/organizations/{orgId}/domains") public MailDomain addDomain(@RequestHeader("X-Tuku-Provisioning-Key") String key,@PathVariable UUID orgId,@Valid @RequestBody DomainRequest r){ requireKey(key); return provisioning.addDomain(orgId,r.domain(),r.verified()); }
    @PostMapping("/organizations/{orgId}/mailboxes") public MailboxResponse createMailbox(@RequestHeader("X-Tuku-Provisioning-Key") String key,@PathVariable UUID orgId,@Valid @RequestBody MailboxRequest r){ requireKey(key); var c=provisioning.createMailbox(orgId,r.localPart(),r.domain(),r.displayName(),r.quotaGb(),r.staffRef()); return new MailboxResponse(c.mailbox(),c.temporaryPassword()); }
    @GetMapping("/organizations/{orgId}/mailboxes") public List<Mailbox> listMailboxes(@RequestHeader("X-Tuku-Provisioning-Key") String key,@PathVariable UUID orgId){ requireKey(key); return provisioning.list(orgId); }
    @PostMapping("/mailboxes/{id}/suspend") public Mailbox suspend(@RequestHeader("X-Tuku-Provisioning-Key") String key,@PathVariable UUID id){ requireKey(key); return provisioning.suspend(id); }

    private void requireKey(String value){ if(!java.security.MessageDigest.isEqual(provisioningKey.getBytes(),value.getBytes()))throw new IllegalArgumentException("Invalid provisioning key"); }
    private static String token(String auth){ if(auth==null||!auth.startsWith("Bearer "))throw new IllegalArgumentException("Missing session"); return auth.substring(7); }
    public record LoginRequest(@Email String email,@NotBlank String password){}
    public record SessionResponse(String token,String email){}
    public record SendRequest(@NotEmpty List<@Email String> to,List<@Email String> cc,@NotBlank @Size(max=998) String subject,@NotNull @Size(max=2_000_000) String body){}
    public record OrgRequest(@NotBlank String name,@Pattern(regexp="[a-z0-9-]{2,64}") String slug,@NotBlank String plan,@Min(1) int mailboxLimit,@Min(1) int storageLimitGb){}
    public record DomainRequest(@NotBlank String domain,boolean verified){}
    public record MailboxRequest(@NotBlank String localPart,@NotBlank String domain,@NotBlank String displayName,@Min(1) @Max(100) int quotaGb,String staffRef){}
    public record MailboxResponse(Mailbox mailbox,String temporaryPassword){}
}
