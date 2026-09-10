package org.tukutuku.mail.engine;

public record MailboxProvision(String address, String displayName, long quotaBytes) {}
