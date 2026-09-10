package org.tukutuku.mail.engine;

public final class MailEngineException extends RuntimeException {
    public MailEngineException(String message) { super(message); }
    public MailEngineException(String message, Throwable cause) { super(message, cause); }
}
