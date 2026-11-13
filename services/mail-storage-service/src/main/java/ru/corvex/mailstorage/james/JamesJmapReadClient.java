package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final Duration deadline;

    /** HTTPS only, with the JVM's normal certificate and hostname validation. */
    public JamesJmapReadClient(URI origin, ObjectMapper mapper) {
        this(origin, mapper, false);
    }

    /** Package-private HTTP override permits only local loopback unit tests. */
    JamesJmapReadClient(URI origin, ObjectMapper mapper, boolean allowTestLoopback) {
        this(origin, mapper, allowTestLoopback, Duration.ofSeconds(10));
    }

    JamesJmapReadClient(URI origin, ObjectMapper mapper, boolean allowTestLoopback, Duration deadline) {
        Objects.requireNonNull(origin, "origin");
        if (deadline == null || deadline.isNegative() || deadline.isZero() || deadline.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid JMAP deadline");
        }
        this.deadline = deadline;
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
        return HttpRequest.newBuilder(uri).timeout(deadline)
                .header("Accept", "application/json; jmapVersion=rfc-8621")
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        // Wait for the ENTIRE response, not just the headers. ofInputStream()
        // would return early and allow an indefinitely stalled response body.
        var future = http.sendAsync(request, info -> new BoundedBodySubscriber());
        final HttpResponse<byte[]> response;
        try {
            response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("JMAP response timed out", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("JMAP request failed", e.getCause());
        }
        if (response.statusCode() != 200) throw new IOException("JMAP returned HTTP " + response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        if (json == null || !json.isObject()) throw new IOException("Invalid JMAP JSON response");
        return json;
    }

    /** Bounded before allocation; cancels the upstream HTTP stream on overflow. */
    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("JMAP response too large"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
