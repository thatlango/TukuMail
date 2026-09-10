package org.tukutuku.mail.engine;

import java.time.Instant;
import java.util.List;

public record MessageDetail(String id, String from, List<String> to, List<String> cc, String subject, String bodyText, Instant receivedAt, boolean read) {}
