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

/** TukuMail's replaceable Apache James boundary. */
public final class JamesMailEngine implements MailEngine {
    private final URI webAdmin;
    private final String imapHost;
    private final int imapPort;
    private final String smtpHost;
    private final int smtpPort;
    private final HttpClient http = HttpClient.newHttpClient();

    public JamesMailEngine(URI webAdmin, String imapHost, int imapPort, String smtpHost, int smtpPort) {
        this.webAdmin = webAdmin; this.imapHost = imapHost; this.imapPort = imapPort; this.smtpHost = smtpHost; this.smtpPort = smtpPort;
    }

    @Override public void ensureDomain(String domain) { request("PUT", "/domains/" + enc(domain), null, Set.of(204, 409)); }

    @Override public void createMailbox(MailboxProvision provision, char[] password) {
        request("PUT", "/users/" + enc(provision.address()), "{\"password\":\"" + json(new String(password)) + "\"}", Set.of(204));
        if (provision.quotaBytes() > 0) request("PUT", "/quota/users/" + enc(provision.address()) + "/size", Long.toString(provision.quotaBytes()), Set.of(204));
    }

    @Override public void rotatePassword(String address, char[] password) {
        request("PUT", "/users/" + enc(address) + "?force", "{\"password\":\"" + json(new String(password)) + "\"}", Set.of(204));
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
        try { store = connect(address, password); return store.isConnected(); }
        catch (MessagingException e) { return false; }
        finally { close(store); }
    }

    @Override public List<MessageSummary> inbox(String address, char[] password, int limit) {
        Store store = null; Folder folder = null;
        try {
            store = connect(address, password); folder = store.getFolder("INBOX"); folder.open(Folder.READ_ONLY);
            if (!(folder instanceof UIDFolder uidFolder)) throw new MailEngineException("IMAP server does not expose message UIDs");
            int count = folder.getMessageCount(); if (count == 0) return List.of();
            int start = Math.max(1, count - Math.max(1, limit) + 1);
            Message[] messages = folder.getMessages(start, count); List<MessageSummary> out = new ArrayList<>(messages.length);
            for (int i = messages.length - 1; i >= 0; i--) {
                Message m = messages[i];
                out.add(new MessageSummary(Long.toString(uidFolder.getUID(m)), first(m.getFrom(), "Unknown sender"), safe(m.getSubject(), "(No subject)"), preview(m), received(m), m.isSet(Flags.Flag.SEEN)));
            }
            return out;
        } catch (MailEngineException e) { throw e; }
        catch (Exception e) { throw new MailEngineException("Unable to read inbox", e); }
        finally { close(folder); close(store); }
    }

    @Override public MessageDetail message(String address, char[] password, String id) {
        Store store = null; Folder folder = null;
        try {
            store = connect(address, password); folder = store.getFolder("INBOX"); folder.open(Folder.READ_ONLY);
            if (!(folder instanceof UIDFolder uidFolder)) throw new MailEngineException("IMAP server does not expose message UIDs");
            Message m = uidFolder.getMessageByUID(Long.parseLong(id));
            if (m == null) throw new MailEngineException("Message not found");
            return new MessageDetail(id, first(m.getFrom(), "Unknown sender"), addresses(m.getRecipients(Message.RecipientType.TO)), addresses(m.getRecipients(Message.RecipientType.CC)), safe(m.getSubject(), "(No subject)"), body(m), received(m), m.isSet(Flags.Flag.SEEN));
        } catch (MailEngineException e) { throw e; }
        catch (Exception e) { throw new MailEngineException("Unable to read message", e); }
        finally { close(folder); close(store); }
    }

    @Override public void send(String address, char[] password, OutgoingMessage outgoing) {
        try {
            Properties p = new Properties(); p.put("mail.smtp.host", smtpHost); p.put("mail.smtp.port", Integer.toString(smtpPort)); p.put("mail.smtp.auth", "true"); p.put("mail.smtp.ssl.enable", "true"); p.put("mail.smtp.ssl.checkserveridentity", "true");
            Session session = Session.getInstance(p, new Authenticator(){ @Override protected PasswordAuthentication getPasswordAuthentication(){ return new PasswordAuthentication(address, new String(password)); }});
            MimeMessage m = new MimeMessage(session); m.setFrom(new InternetAddress(address));
            for(String to: outgoing.to()) m.addRecipient(Message.RecipientType.TO,new InternetAddress(to));
            for(String cc: outgoing.cc()) m.addRecipient(Message.RecipientType.CC,new InternetAddress(cc));
            m.setSubject(outgoing.subject(), StandardCharsets.UTF_8.name()); m.setText(outgoing.textBody(), StandardCharsets.UTF_8.name()); m.setSentDate(new Date()); Transport.send(m);
        } catch (MessagingException e) { throw new MailEngineException("Unable to send message", e); }
    }

    private Store connect(String address, char[] password) throws MessagingException {
        Session session=Session.getInstance(imapProperties()); Store store=session.getStore("imaps"); store.connect(imapHost,imapPort,address,new String(password)); return store;
    }
    private Properties imapProperties(){ Properties p=new Properties(); p.put("mail.imaps.ssl.enable","true"); p.put("mail.imaps.ssl.checkserveridentity","true"); p.put("mail.imaps.connectiontimeout","10000"); p.put("mail.imaps.timeout","15000"); return p; }
    private static Instant received(Message m) throws MessagingException { return Optional.ofNullable(m.getReceivedDate()).orElse(new Date()).toInstant(); }
    private static String first(Address[] a,String fallback){ return a==null||a.length==0?fallback:a[0].toString(); }
    private static List<String> addresses(Address[] a){ if(a==null)return List.of(); return Arrays.stream(a).map(Object::toString).toList(); }
    private static String safe(String s,String fallback){ return s==null||s.isBlank()?fallback:s; }
    private String preview(Message m){ String v=body(m).replaceAll("\\s+"," ").trim(); return v.length()>180?v.substring(0,180)+"…":v; }
    private String body(Part p){
        try {
            if(p.isMimeType("text/plain") && p.getContent() instanceof String s) return s;
            if(p.isMimeType("text/html") && p.getContent() instanceof String s) return s.replaceAll("<[^>]+>"," ").replace("&nbsp;"," ");
            if(p.isMimeType("multipart/*") && p.getContent() instanceof Multipart mp){
                String html=""; for(int i=0;i<mp.getCount();i++){ BodyPart part=mp.getBodyPart(i); String text=body(part); if(part.isMimeType("text/plain")&&!text.isBlank())return text; if(!text.isBlank())html=text; } return html;
            }
        } catch(Exception ignored){} return "";
    }
    private void request(String method,String path,String body,Set<Integer> accepted){
        try { HttpRequest.Builder b=HttpRequest.newBuilder(webAdmin.resolve(path)); if(body!=null)b.header("Content-Type","application/json"); b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)); HttpResponse<String> r=http.send(b.build(),HttpResponse.BodyHandlers.ofString()); if(!accepted.contains(r.statusCode()))throw new MailEngineException("Mail engine returned "+r.statusCode()+": "+r.body()); }
        catch(MailEngineException e){throw e;} catch(Exception e){throw new MailEngineException("Mail engine request failed",e);}
    }
    private static String enc(String v){return URLEncoder.encode(v,StandardCharsets.UTF_8);} private static String json(String v){return v.replace("\\","\\\\").replace("\"","\\\"");}
    private static void close(Folder f){if(f!=null)try{if(f.isOpen())f.close(false);}catch(Exception ignored){}} private static void close(Store s){if(s!=null)try{s.close();}catch(Exception ignored){}}
}
