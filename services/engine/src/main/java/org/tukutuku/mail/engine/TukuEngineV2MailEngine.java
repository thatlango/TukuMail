package org.tukutuku.mail.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Adapter from the stable TukuMail MailEngine contract to Tuku Engine v2.
 * The Engine v2 management API is private to the TukuMail service network.
 */
public final class TukuEngineV2MailEngine implements MailEngine {
    private final URI endpoint;
    private final String engineKey;
    private final HttpClient http = HttpClient.newBuilder().build();
    private final ObjectMapper json = new ObjectMapper();

    public TukuEngineV2MailEngine(URI endpoint, String engineKey) {
        this.endpoint = endpoint;
        this.engineKey = Objects.requireNonNull(engineKey, "engineKey");
    }

    @Override public void ensureDomain(String domain) {
        request("PUT", "/v1/domains/" + enc(domain), null, Set.of(204));
    }

    @Override public void createMailbox(MailboxProvision provision, char[] password) {
        request("PUT", "/v1/mailboxes/" + enc(provision.address()), Map.of(
            "displayName", provision.displayName(),
            "password", new String(password),
            "quotaBytes", provision.quotaBytes()
        ), Set.of(204));
    }

    @Override public void rotatePassword(String address, char[] password) {
        request("PUT", "/v1/mailboxes/" + enc(address) + "/password",
            Map.of("password", new String(password)), Set.of(204));
    }

    @Override public void suspendMailbox(String address) {
        request("POST", "/v1/mailboxes/" + enc(address) + "/suspend", null, Set.of(204));
    }

    @Override public void createAlias(String destination, String alias) {
        request("PUT", "/v1/aliases/" + enc(alias), Map.of("destination", destination), Set.of(204));
    }

    @Override public boolean authenticate(String address, char[] password) {
        JsonNode node = request("POST", "/v1/auth", Map.of(
            "address", address,
            "password", new String(password)
        ), Set.of(200));
        return node.path("authenticated").asBoolean(false);
    }

    @Override public List<MessageSummary> inbox(String address, char[] password, int limit) {
        requireAuthentication(address, password);
        JsonNode node = request("GET",
            "/v1/mailboxes/" + enc(address) + "/messages?limit=" + Math.max(1, Math.min(limit, 100)),
            null, Set.of(200));
        List<MessageSummary> out = new ArrayList<>();
        for (JsonNode item : node) {
            out.add(new MessageSummary(
                item.path("id").asText(),
                item.path("from").asText(),
                item.path("subject").asText(),
                item.path("preview").asText(),
                Instant.parse(item.path("receivedAt").asText()),
                item.path("read").asBoolean()
            ));
        }
        return out;
    }

    @Override public MessageDetail message(String address, char[] password, String id) {
        requireAuthentication(address, password);
        JsonNode item = request("GET",
            "/v1/mailboxes/" + enc(address) + "/messages/" + enc(id),
            null, Set.of(200));
        return new MessageDetail(
            item.path("id").asText(),
            item.path("from").asText(),
            strings(item.path("to")),
            strings(item.path("cc")),
            item.path("subject").asText(),
            item.path("bodyText").asText(),
            Instant.parse(item.path("receivedAt").asText()),
            item.path("read").asBoolean()
        );
    }

    @Override public void send(String address, char[] password, OutgoingMessage message) {
        requireAuthentication(address, password);
        request("POST", "/v1/mailboxes/" + enc(address) + "/send", Map.of(
            "to", message.to(),
            "cc", message.cc(),
            "subject", message.subject(),
            "textBody", message.textBody()
        ), Set.of(202));
    }

    private void requireAuthentication(String address, char[] password) {
        if (!authenticate(address, password)) throw new MailEngineException("Authentication failed");
    }

    private JsonNode request(String method, String path, Object body, Set<Integer> accepted) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .header("X-Tuku-Engine-Key", engineKey)
                .header("Accept", "application/json");
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
            if (body != null) {
                builder.header("Content-Type", "application/json");
                publisher = HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            }
            HttpResponse<String> response = http.send(
                builder.method(method, publisher).build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (!accepted.contains(response.statusCode())) {
                throw new MailEngineException("Tuku Engine v2 returned " + response.statusCode() + ": " + response.body());
            }
            return response.body() == null || response.body().isBlank()
                ? json.createObjectNode()
                : json.readTree(response.body());
        } catch (MailEngineException e) {
            throw e;
        } catch (Exception e) {
            throw new MailEngineException("Tuku Engine v2 request failed", e);
        }
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(v -> out.add(v.asText()));
        return List.copyOf(out);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
