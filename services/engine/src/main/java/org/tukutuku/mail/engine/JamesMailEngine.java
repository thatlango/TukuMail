package org.tukutuku.mail.engine;

import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * TukuMail's Apache James boundary. No Tuku client or product integration calls
 * James directly. This adapter can later be replaced by an embedded James
 * protocol composition without changing TukuMail's domain contracts.
 */
public final class JamesMailEngine implements MailEngine {
    private final URI webAdmin;
    private final String imapHost;
    private final int imapPort;
    private final String smtpHost;
    private final int smtpPort;
    private final HttpClient http = HttpClient.newHttpClient();

    public JamesMailEngine(URI webAdmin, String imapHost, int imapPort, String smtpHost, int smtpPort) {
        this.webAdmin = webAdmin;
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
    }

    @Override public void ensureDomain(String domain) {
        request("PUT", "/domains/" + enc(domain), null, Set.of(204));
    }

    @Override public void createMailbox(MailboxProvision provision, char[] password) {
        String body = "{\"password\":\"" + json(new String(password)) + "\"}";
        request("PUT", "/users/" + enc(provision.address()), body, Set.of(204));
        if (provision.quotaBytes() > 0) {
            request("PUT", "/quota/users/" + enc(provision.address()) + "/size", Long.toString(provision.quotaBytes()), Set.of(204));
        }
    }

    @Override public void rotatePassword(String address, char[] password) {
        String body = "{\"password\":\"" + json(new String(password)) + "\"}";
        request("PUT", "/users/" + enc(address) + "?force", body, Set.of(204));
    }

    @Override public void suspendMailbox(String address) {
        rotatePassword(address, ("suspended-" + UUID.randomUUID()).toCharArray());
        request("DELETE", "/servers/channels/" + enc(address), null, Set.of(204, 404));
    }

    @Override public void createAlias(String destination, String alias) {
        request("PUT", "/address/aliases/" + enc(destination) + "/sources/" + enc(alias), null, Set.of(204));
    }

    @Override public boolean authenticate(String address, char[] password) {
        Store store = null;
        try {
            Session session = Session.getInstance(imapProperties());
            store = session.getStore("imaps");
            store.connect(imapHost, imapPort, address, new String(password));
            return store.isConnected();
        } catch (MessagingException e) {
            return false;
        } finally {
            close(store);
        }
    }

    @Override public List<MessageSummary> inbox(String address, char[] password, int limit) {
        Store store = null;
        Folder inbox = null;
        try {
            Session session = Session.getInstance(imapProperties());
            store = session.getStore("imaps");
            store.connect(imapHost, imapPort, address, new String(password));
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            int count = inbox.getMessageCount();
            if (count == 0) return List.of();
            int start = Math.max(1, count - Math.max(1, limit) + 1);
            Message[] messages = inbox.getMessages(start, count);
            List<MessageSummary> out = new ArrayList<>(messages.length);
            for (int i = messages.length - 1; i >= 0; i--) {
                Message m = messages[i];
                String from = m.getFrom() == null || m.getFrom().length == 0 ? "Unknown sender" : m.getFrom()[0].toString();
                String subject = Optional.ofNullable(m.getSubject()).orElse("(No subject)");
                String preview = preview(m);
                Instant received = Optional.ofNullable(m.getReceivedDate()).orElse(new Date()).toInstant();
                out.add(new MessageSummary(Integer.toString(m.getMessageNumber()), from, subject, preview, received, m.isSet(Flags.Flag.SEEN)));
            }
            return out;
        } catch (Exception e) {
            throw new MailEngineException("Unable to read inbox", e);
        } finally {
            close(inbox); close(store);
        }
    }

    @Override public void send(String address, char[] password, OutgoingMessage outgoing) {
        try {
            Properties p = new Properties();
            p.put("mail.smtp.host", smtpHost);
            p.put("mail.smtp.port", Integer.toString(smtpPort));
            p.put("mail.smtp.auth", "true");
            p.put("mail.smtp.ssl.enable", "true");
            p.put("mail.smtp.ssl.checkserveridentity", "true");
            Session session = Session.getInstance(p, new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(address, new String(password));
                }
            });
            MimeMessage m = new MimeMessage(session);
            m.setFrom(new InternetAddress(address));
            for (String to : outgoing.to()) m.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            for (String cc : outgoing.cc()) m.addRecipient(Message.RecipientType.CC, new InternetAddress(cc));
            m.setSubject(outgoing.subject(), StandardCharsets.UTF_8.name());
            m.setText(outgoing.textBody(), StandardCharsets.UTF_8.name());
            m.setSentDate(new Date());
            Transport.send(m);
        } catch (MessagingException e) {
            throw new MailEngineException("Unable to send message", e);
        }
    }

    private Properties imapProperties() {
        Properties p = new Properties();
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.connectiontimeout", "10000");
        p.put("mail.imaps.timeout", "15000");
        return p;
    }

    private String preview(Message message) {
        try {
            Object content = message.getContent();
            if (content instanceof String s) return compact(s);
            if (content instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart p = mp.getBodyPart(i);
                    if (p.isMimeType("text/plain") && p.getContent() instanceof String s) return compact(s);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String compact(String s) {
        String v = s.replaceAll("\\s+", " ").trim();
        return v.length() > 180 ? v.substring(0, 180) + "…" : v;
    }

    private void request(String method, String path, String body, Set<Integer> accepted) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(webAdmin.resolve(path));
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (!accepted.contains(r.statusCode())) throw new MailEngineException("Mail engine returned " + r.statusCode() + ": " + r.body());
        } catch (MailEngineException e) { throw e; }
        catch (Exception e) { throw new MailEngineException("Mail engine request failed", e); }
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String json(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static void close(Folder f) { if (f != null) try { if (f.isOpen()) f.close(false); } catch (Exception ignored) {} }
    private static void close(Store s) { if (s != null) try { s.close(); } catch (Exception ignored) {} }
}
