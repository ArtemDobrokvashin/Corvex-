package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Dormant read-only JMAP transport, not a Spring component or a public API.
 * It does NOT validate Corvex JWTs, issue James tokens, map identities, enforce
 * owner permissions, or establish that a given account belongs to the caller.
 * Do not wire it into production before those separate security controls exist.
 */
public final class JamesJmapReadClient {
    private static final String CORE = "urn:ietf:params:jmap:core";
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_REQUEST_BYTES = 256 * 1024;
    private static final Set<String> READ_METHODS = Set.of("Mailbox/get", "Email/query", "Email/get");
    private final URI sessionUri;
    private final URI apiUri;
    private final ObjectMapper mapper;
    private final HttpClient http;

    /** HTTPS only, with the JVM's normal certificate and hostname validation. */
    public JamesJmapReadClient(URI origin, ObjectMapper mapper) {
        this(origin, mapper, false);
    }

    /** Package-private HTTP override permits only local loopback unit tests. */
    JamesJmapReadClient(URI origin, ObjectMapper mapper, boolean allowTestLoopback) {
        Objects.requireNonNull(origin, "origin");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        boolean https = "https".equalsIgnoreCase(origin.getScheme());
        boolean localTest = allowTestLoopback && "http".equalsIgnoreCase(origin.getScheme())
                && "127.0.0.1".equals(origin.getHost());
        if ((!https && !localTest) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getPath().isEmpty() || origin.getPath().equals("/"))) {
            throw new IllegalArgumentException("JMAP requires an HTTPS origin without a path or credentials");
        }
        this.sessionUri = origin.resolve("/jmap/session");
        this.apiUri = origin.resolve("/jmap");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode session(String jamesBearerToken) throws IOException, InterruptedException {
        HttpRequest request = builder(sessionUri, jamesBearerToken).GET().build();
        JsonNode result = send(request);
        if (!result.isObject() || !result.path("primaryAccounts").isObject()) {
            throw new IOException("Invalid JMAP session response");
        }
        return result;
    }

    /** Read methods only. The caller must bind and authorize accountId independently. */
    public JsonNode read(String jamesBearerToken, String method, ObjectNode arguments)
            throws IOException, InterruptedException {
        if (method == null || !READ_METHODS.contains(method) || arguments == null
                || !arguments.path("accountId").isTextual() || arguments.path("accountId").asText().isBlank()) {
            throw new IllegalArgumentException("Invalid JMAP read method or account");
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("using").add(CORE).add(MAIL);
        payload.putArray("methodCalls").addArray().add(method).add(arguments.deepCopy()).add("corvex-read");
        byte[] json = mapper.writeValueAsBytes(payload);
        if (json.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("JMAP request too large");
        HttpRequest request = builder(apiUri, jamesBearerToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
        JsonNode responses = send(request).path("methodResponses");
        if (!responses.isArray() || responses.size() != 1 || !responses.get(0).isArray()
                || responses.get(0).size() != 3 || !method.equals(responses.get(0).get(0).asText())
                || !responses.get(0).get(1).isObject()
                || !"corvex-read".equals(responses.get(0).get(2).asText())) {
            // JMAP method-level errors (including accountNotFound) fail closed.
            throw new IOException("JMAP method failed or returned an invalid response");
        }
        return responses.get(0).get(1);
    }

    private HttpRequest.Builder builder(URI uri, String token) {
        if (token == null || token.length() > 8192 || !token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new IllegalArgumentException("Invalid James bearer token");
        }
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json; jmapVersion=rfc-8621")
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("JMAP returned HTTP " + response.statusCode());
            byte[] bytes = body.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("JMAP response too large");
            JsonNode json = mapper.readTree(bytes);
            if (json == null || !json.isObject()) throw new IOException("Invalid JMAP JSON response");
            return json;
        }
    }
}
