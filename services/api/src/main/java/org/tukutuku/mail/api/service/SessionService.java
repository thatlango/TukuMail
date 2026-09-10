package org.tukutuku.mail.api.service;

import org.springframework.stereotype.Service;
import org.tukutuku.mail.engine.MailEngine;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {
    public record Credentials(String address, char[] password, Instant expiresAt) {}
    private final Map<String, Credentials> sessions = new ConcurrentHashMap<>();
    private final MailEngine engine;
    public SessionService(MailEngine engine) { this.engine = engine; }
    public String login(String address, char[] password) {
        if (!engine.authenticate(address, password)) throw new IllegalArgumentException("Invalid email or password");
        String token = UUID.randomUUID().toString();
        sessions.put(token, new Credentials(address.toLowerCase(Locale.ROOT), password.clone(), Instant.now().plusSeconds(12*3600)));
        return token;
    }
    public Credentials require(String token) {
        Credentials c = sessions.get(token);
        if (c == null || c.expiresAt().isBefore(Instant.now())) { sessions.remove(token); throw new IllegalArgumentException("Session expired"); }
        return c;
    }
    public void logout(String token) { Credentials c=sessions.remove(token); if(c!=null) Arrays.fill(c.password(), '\0'); }
}
