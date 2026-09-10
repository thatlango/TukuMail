package org.tukutuku.mail.extensions;

import jakarta.inject.Inject;
import jakarta.mail.MessagingException;
import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.jdkim.mailets.DKIMSign;
import org.apache.mailet.Mail;
import org.apache.mailet.MailetConfig;
import org.apache.mailet.MailetContext;
import org.apache.mailet.base.GenericMailet;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TukuMail's multi-domain DKIM boundary.
 *
 * One James node can host many customer domains. Apache James 3.9's stock
 * DKIMSign accepts one static signing domain/key per mailet instance. This
 * wrapper selects and caches one DKIMSign instance per envelope-sender domain,
 * with keys stored under a Tuku-owned directory. The mail engine therefore
 * remains standards-compatible without hard-coding one customer's domain.
 */
public final class MultiDomainDkimSign extends GenericMailet {
    private final FileSystem fileSystem;
    private final Map<String, DKIMSign> signers = new ConcurrentHashMap<>();
    private String keyRoot;
    private String selector;

    @Inject
    public MultiDomainDkimSign(FileSystem fileSystem) {
        this.fileSystem = fileSystem;
    }

    @Override
    public void init() {
        keyRoot = normalizeRoot(getInitParameter("keyRoot", "file:///root/dkim/"));
        selector = getInitParameter("selector", "tuku1").trim().toLowerCase(Locale.ROOT);
        if (!selector.matches("[a-z0-9][a-z0-9_-]{0,62}")) {
            throw new IllegalArgumentException("Invalid DKIM selector");
        }
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        String domain = mail.getMaybeSender().asOptional()
            .map(address -> address.getDomain().asString().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new MessagingException("Cannot DKIM-sign mail without an envelope sender"));

        signerFor(domain).service(mail);
    }

    private DKIMSign signerFor(String domain) throws MessagingException {
        DKIMSign existing = signers.get(domain);
        if (existing != null) return existing;

        synchronized (signers) {
            existing = signers.get(domain);
            if (existing != null) return existing;

            DKIMSign created = new DKIMSign(fileSystem);
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("signatureTemplate",
                "v=1; s=" + selector + "; d=" + domain +
                "; h=from:reply-to:subject:date:to:cc:mime-version:content-type:message-id; " +
                "a=rsa-sha256; c=relaxed/relaxed; bh=; b=;");
            parameters.put("privateKeyFilepath", keyRoot + domain + "/private.pem");
            parameters.put("forceCRLF", "true");
            created.init(new DelegateConfig(getMailetName() + "-" + domain, getMailetContext(), parameters));
            signers.put(domain, created);
            return created;
        }
    }

    private static String normalizeRoot(String root) {
        String trimmed = root.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    private record DelegateConfig(String name, MailetContext context, Map<String, String> parameters) implements MailetConfig {
        @Override public String getInitParameter(String key) { return parameters.get(key); }
        @Override public Iterator<String> getInitParameterNames() { return parameters.keySet().iterator(); }
        @Override public MailetContext getMailetContext() { return context; }
        @Override public String getMailetName() { return name; }
    }
}
