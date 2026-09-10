package org.tukutuku.mail.api.service;

import org.springframework.stereotype.Service;

import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;

@Service
public class DnsVerificationService {
    public static final String PREFIX = "tukumail-verification=";

    public boolean verifies(String domain, String token) {
        String record = "_tukumail-verification." + domain;
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2500");
        env.put("com.sun.jndi.dns.timeout.retries", "2");
        try {
            DirContext context = new InitialDirContext(env);
            Attributes attributes = context.getAttributes(record, new String[]{"TXT"});
            Attribute txt = attributes.get("TXT");
            if (txt == null) return false;
            NamingEnumeration<?> values = txt.getAll();
            String expected = PREFIX + token;
            while (values.hasMore()) {
                String value = values.next().toString().replace("\"", "").trim();
                if (expected.equals(value)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
