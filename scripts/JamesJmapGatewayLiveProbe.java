package ru.corvex.mailstorage.james;

// A read-only probe for the previously created synthetic James account. The
// deliberately invalid write requests contain no mutations or user data; they
// must be rejected by the gateway BEFORE reaching James. No token is printed.
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class JamesJmapGatewayLiveProbe {
    private static final URI ORIGIN = URI.create("https://james-jmap:8443");
    private static final String CORE = "urn:ietf:params:jmap:core";
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path ADAPTER = Path.of("/run/james-adapter");

    private static SSLContext tls() throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (var in = Files.newInputStream(ADAPTER.resolve("jmap-trust.crt"))) {
            store.setCertificateEntry("jmap", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        var context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }

    private static RSAPrivateKey key() throws Exception {
        String pem = Files.readString(ADAPTER.resolve("jwt-private.pem"), StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN PRIVATE KEY-----", end = "-----END PRIVATE KEY-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalStateException("Invalid key");
        byte[] data = Base64.getDecoder().decode(pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", ""));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(data));
    }

    private static ObjectNode request(String method) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("using").add(CORE).add(MAIL);
        // No account, blob, create/update/destroy records or other mutations.
        var call = root.putArray("methodCalls").addArray();
        call.add(method);
        call.addObject().put("invalidProbe", true);
        call.add("corvex-read");
        return root;
    }

    private static void expectRejected(HttpClient client, String token, ObjectNode body) throws Exception {
        var request = HttpRequest.newBuilder(ORIGIN.resolve("/jmap"))
                .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body))).build();
        var future = client.sendAsync(request, info -> new JamesJmapReadClient.BoundedBodySubscriber());
        final HttpResponse<byte[]> response;
        try { response = future.get(15, TimeUnit.SECONDS); }
        finally { if (!future.isDone()) future.cancel(true); }
        if (response.statusCode() != 400
                || !response.headers().firstValue("content-type").orElse("").equals("text/plain; charset=utf-8")
                || !new String(response.body(), StandardCharsets.UTF_8).equals("Request rejected")) {
            throw new IllegalStateException("Write request was not rejected by candidate gateway");
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1 || args.length > 2 || !args[0].matches("cvx-[0-9a-f]{32}@localhost")
                    || (args.length == 2 && !args[1].equals("read-only"))) {
                throw new IllegalArgumentException("Invalid synthetic probe arguments");
            }
            var ssl = tls();
            UUID id = UUID.randomUUID();
            var principal = new JamesMailboxBinding.Principal(id, Set.of("MAIL_READ_OWN"));
            var mapping = new JamesMailboxBinding.ProvisionedMailbox(id, true, args[0], "synthetic-only");
            var issuer = new JamesReadTokenIssuer(key(), Clock.systemUTC(), MAPPER);
            String token = issuer.issue(principal, mapping);
            var reader = new JamesJmapReadClient(ORIGIN, MAPPER, ssl);
            var account = reader.session(token).path("primaryAccounts").path(MAIL);
            if (!account.isTextual() || account.textValue().isBlank()) throw new IllegalStateException("Missing synthetic primary account");
            var folders = reader.read(token, "Mailbox/get", MAPPER.createObjectNode().put("accountId", account.textValue()));
            if (!account.textValue().equals(folders.path("accountId").asText()) || !folders.path("list").isArray()) {
                throw new IllegalStateException("Invalid mailbox response");
            }
            var query = reader.read(token, "Email/query", MAPPER.createObjectNode()
                    .put("accountId", account.textValue()).put("limit", 1).put("position", 0));
            var ids = query.path("ids");
            if (!account.textValue().equals(query.path("accountId").asText()) || !ids.isArray() || ids.size() > 1) {
                throw new IllegalStateException("Invalid synthetic email query");
            }
            if (!ids.isEmpty()) {
                if (!ids.get(0).isTextual() || ids.get(0).asText().isBlank()) throw new IllegalStateException("Invalid email id");
                var get = MAPPER.createObjectNode().put("accountId", account.textValue());
                get.set("ids", ids.deepCopy());
                get.putArray("properties").add("id");
                var messages = reader.read(token, "Email/get", get);
                if (!account.textValue().equals(messages.path("accountId").asText()) || !messages.path("list").isArray()) {
                    throw new IllegalStateException("Invalid synthetic email response");
                }
                System.out.println("PASS: synthetic Email/get");
            } else {
                System.out.println("SKIP: synthetic mailbox empty; Email/get not exercised");
            }
            System.out.println("PASS: synthetic authenticated JMAP session, Mailbox/get and Email/query");
            if (args.length == 2) return;
            var http = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            for (String method : new String[]{"Email/import", "Email/set", "Mailbox/set", "EmailSubmission/set"}) {
                expectRejected(http, token, request(method));
            }
            var mixed = request("Email/get");
            mixed.withArray("methodCalls").addArray().add("Email/import").addObject().put("invalidProbe", true);
            ((com.fasterxml.jackson.databind.node.ArrayNode) mixed.withArray("methodCalls").get(1)).add("x");
            expectRejected(http, token, mixed);
            System.out.println("PASS: synthetic non-mutating write probes and mixed batch rejected with HTTP 400");
        } catch (Exception error) {
            System.err.println("FAIL: live gateway probe; no mail changes requested");
            System.exit(1);
        }
    }
}
