package org.tukutuku.mail.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tukutuku.mail.engine.JamesMailEngine;
import org.tukutuku.mail.engine.MailEngine;
import org.tukutuku.mail.engine.TukuEngineV2MailEngine;

import java.net.URI;

@Configuration
public class EngineConfiguration {
    @Bean MailEngine mailEngine(
            @Value("${tukumail.engine.provider:james}") String provider,
            @Value("${tukumail.engine.webadmin}") String admin,
            @Value("${tukumail.engine.imap-host}") String imapHost,
            @Value("${tukumail.engine.imap-port}") int imapPort,
            @Value("${tukumail.engine.smtp-host}") String smtpHost,
            @Value("${tukumail.engine.smtp-port}") int smtpPort,
            @Value("${tukumail.engine.v2-endpoint:http://localhost:8088}") String v2Endpoint,
            @Value("${tukumail.engine.v2-key:dev-only-change-me-engine-v2}") String v2Key) {
        return switch (provider.trim().toLowerCase()) {
            case "v2", "tuku-v2", "tuku" -> new TukuEngineV2MailEngine(URI.create(v2Endpoint), v2Key);
            case "james" -> new JamesMailEngine(URI.create(admin), imapHost, imapPort, smtpHost, smtpPort);
            default -> throw new IllegalArgumentException("Unknown TukuMail engine provider: " + provider);
        };
    }
}
