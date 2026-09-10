package org.tukutuku.mail.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tukutuku.mail.engine.JamesMailEngine;
import org.tukutuku.mail.engine.MailEngine;

import java.net.URI;

@Configuration
public class EngineConfiguration {
    @Bean MailEngine mailEngine(
            @Value("${tukumail.engine.webadmin}") String admin,
            @Value("${tukumail.engine.imap-host}") String imapHost,
            @Value("${tukumail.engine.imap-port}") int imapPort,
            @Value("${tukumail.engine.smtp-host}") String smtpHost,
            @Value("${tukumail.engine.smtp-port}") int smtpPort) {
        return new JamesMailEngine(URI.create(admin), imapHost, imapPort, smtpHost, smtpPort);
    }
}
