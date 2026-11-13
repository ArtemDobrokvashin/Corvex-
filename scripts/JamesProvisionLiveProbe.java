package ru.corvex.mailstorage.james;

// One synthetic account in the already-running laptop James, never a Corvex user.
// Caller records intent before execution and must never automatically retry.
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

class JamesProvisionLiveProbe {
    private static final Path ADAPTER = Path.of("/run/james-adapter");
    private static String secret(Path path) throws Exception {
        String value = Files.readString(path, StandardCharsets.US_ASCII).trim();
        if (!value.matches("[A-Za-z0-9_-]{32,256}")) throw new IllegalStateException("Invalid secret file");
        return value;
    }
    private static SSLContext tls(String trustName, boolean client) throws Exception {
        var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (var in = Files.newInputStream(ADAPTER.resolve(trustName))) {
            trustStore.setCertificateEntry("server", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trustStore);
        javax.net.ssl.KeyManager[] keys = null;
        if (client) {
            char[] pass = secret(ADAPTER.resolve("admin-client-password")).toCharArray();
            try {
                var store = KeyStore.getInstance("PKCS12");
                try (var in = Files.newInputStream(ADAPTER.resolve("admin-client.p12"))) { store.load(in, pass); }
                var manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                manager.init(store, pass);
                keys = manager.getKeyManagers();
            } finally { java.util.Arrays.fill(pass, '\0'); }
        }
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(keys, trust.getTrustManagers(), null);
        return ssl;
    }
    private static RSAPrivateKey signingKey() throws Exception {
        String pem = Files.readString(ADAPTER.resolve("jwt-private.pem"), StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN PRIVATE KEY-----", end = "-----END PRIVATE KEY-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalStateException("Invalid signing key");
        byte[] der = Base64.getDecoder().decode(pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", ""));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
    public static void main(String[] args) {
        try {
            if (args.length != 2 || !args[0].matches("cvx-[0-9a-f]{32}@localhost")) throw new IllegalArgumentException("Invalid synthetic probe arguments");
            String login = args[0], password = secret(Path.of(args[1]));
            var mapper = new ObjectMapper();
            try (var admin = new JamesWebAdminCreateClient(URI.create("https://james-admin:8443"),
                    secret(ADAPTER.resolve("admin-secret")), mapper, tls("admin-trust.crt", true))) {
                if (!admin.create(login, password)) throw new IllegalStateException("Creation not confirmed");
            }
            System.out.println("PASS: synthetic account creation acknowledged; no automatic retry");
            var owner = UUID.randomUUID();
            var principal = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
            var mapping = new JamesMailboxBinding.ProvisionedMailbox(owner, true, login, "pending");
            var issuer = new JamesReadTokenIssuer(signingKey(), Clock.systemUTC(), mapper);
            String token = issuer.issue(principal, mapping);
            var jmap = new JamesJmapReadClient(URI.create("https://james-jmap:8443"), mapper, tls("jmap-trust.crt", false));
            var primary = jmap.session(token).path("primaryAccounts").path("urn:ietf:params:jmap:mail");
            if (!primary.isTextual() || primary.textValue().isBlank()) throw new IllegalStateException("Missing primary account");
            var folders = jmap.read(token, "Mailbox/get", mapper.createObjectNode().put("accountId", primary.textValue()));
            if (!primary.textValue().equals(folders.path("accountId").asText()) || !folders.path("list").isArray()) {
                throw new IllegalStateException("Invalid mailbox response");
            }
            System.out.println("PASS: signed JWT, dedicated JMAP TLS, session and Mailbox/get");
            System.out.println("PASS: synthetic account only; no Corvex user, registry binding or mail switched");
        } catch (Exception failure) {
            // No stack trace, URLs, tokens, headers or secret values in output.
            System.err.println("FAIL: synthetic create/JMAP outcome unknown; preserve intent, do not retry automatically");
            System.exit(1);
        }
    }
}
