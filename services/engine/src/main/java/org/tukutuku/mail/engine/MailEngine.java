package org.tukutuku.mail.engine;

import java.util.List;

public interface MailEngine {
    void ensureDomain(String domain);
    void createMailbox(MailboxProvision provision, char[] password);
    void rotatePassword(String address, char[] password);
    void suspendMailbox(String address);
    void createAlias(String destination, String alias);
    boolean authenticate(String address, char[] password);
    List<MessageSummary> inbox(String address, char[] password, int limit);
    MessageDetail message(String address, char[] password, String id);
    void send(String address, char[] password, OutgoingMessage message);
}
