package org.tukutuku.mail.engine;

import java.util.List;

public record OutgoingMessage(List<String> to, List<String> cc, String subject, String textBody) {
    public OutgoingMessage {
        to = to == null ? List.of() : List.copyOf(to);
        cc = cc == null ? List.of() : List.copyOf(cc);
        subject = subject == null ? "" : subject;
        textBody = textBody == null ? "" : textBody;
    }
}
