package org.tukutuku.mail.engine;

import java.time.Instant;

public record MessageSummary(String id, String from, String subject, String preview, Instant receivedAt, boolean read) {}
