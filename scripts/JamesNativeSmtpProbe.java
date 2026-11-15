package ru.corvex.mailstorage.james;

// Synthetic probe only, intended for the existing Corvex James container.
// send: fixed loopback SMTP endpoint and synthetic recipient, exactly one send.
// read: JMAP over existing dedicated TLS proxy, no account credentials exposed.
// It is not installed, run or wired into Compose automatically.
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

class JamesNativeSmtpProbe {
    private static final Path ADAPTER = Path.of("/run/james-adapter");
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static SSLContext jmapTls() throws Exception {
        var store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (var input = Files.newInputStream(ADAPTER.resolve("jmap-trust.crt"))) {
            store.setCertificateEntry("jmap", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        return tls;
    }

    private static RSAPrivateKey signingKey() throws Exception {
        String pem = Files.readString(ADAPTER.resolve("jwt-private.pem"), StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN PRIVATE KEY-----", end = "-----END PRIVATE KEY-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalStateException("Invalid key");
        byte[] bytes = Base64.getDecoder().decode(pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", ""));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static void send(String login, String marker) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "10000");
        properties.setProperty("mail.smtp.writetimeout", "10000");
        properties.setProperty("mail.smtp.auth", "false");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        properties.setProperty("mail.debug", "false");
        Session session = Session.getInstance(properties);
        session.setDebug(false);
        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress("synthetic@localhost", true));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(login, true));
        message.setSubject("Corvex native probe " + marker, "UTF-8");
        message.setText("Synthetic message; not user mail.", "UTF-8");
        message.saveChanges();
        try (var transport = session.getTransport("smtp")) {
            // Not a configurable host: cannot send to an external system.
            transport.connect("127.0.0.1", 2525, null, null);
            transport.sendMessage(message, message.getAllRecipients());
        }
        System.out.println("PASS: one synthetic message accepted by local James SMTP");
    }

    private static void read(String login, String marker) throws Exception {
        UUID owner = UUID.randomUUID();
        var principal = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
        var mapping = new JamesMailboxBinding.ProvisionedMailbox(owner, true, login, "synthetic-only");
        var issuer = new JamesReadTokenIssuer(signingKey(), Clock.systemUTC(), mapper);
        var client = new JamesJmapReadClient(URI.create("https://james-jmap:8443"), mapper, jmapTls());
        String token = issuer.issue(principal, mapping);
        var primary = client.session(token).path("primaryAccounts").path(MAIL);
        if (!primary.isTextual() || primary.textValue().isBlank()) throw new IllegalStateException("Invalid session");
        String account = primary.textValue();
        for (int attempt = 0; attempt < 20; attempt++) {
            var query = mapper.createObjectNode().put("accountId", account).put("limit", 100).put("position", 0);
            query.putObject("filter").put("subject", "Corvex native probe " + marker);
            var response = client.read(token, "Email/query", query);
            var ids = response.path("ids");
            if (!account.equals(response.path("accountId").asText()) || !ids.isArray() || ids.size() > 100) {
                throw new IllegalStateException("Invalid query");
            }
            if (!ids.isEmpty()) {
                var request = mapper.createObjectNode().put("accountId", account);
                request.set("ids", ids.deepCopy());
                request.putArray("properties").add("id").add("subject");
                var messages = client.read(token, "Email/get", request);
                if (!account.equals(messages.path("accountId").asText()) || !messages.path("list").isArray()
                        || !messages.path("notFound").isArray() || !messages.path("notFound").isEmpty()) {
                    throw new IllegalStateException("Invalid message response");
                }
                for (var item : messages.path("list")) {
                    if (("Corvex native probe " + marker).equals(item.path("subject").asText())) {
                        System.out.println("PASS: synthetic SMTP message found via James JMAP");
                        return;
                    }
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Message not observed by deadline");
    }

    public static void main(String[] args) {
        try {
            if (args.length != 3 || !args[1].matches("cvx-[0-9a-f]{32}@localhost")
                    || !args[2].matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid synthetic arguments");
            if ("send".equals(args[0])) send(args[1], args[2]);
            else if ("read".equals(args[0])) read(args[1], args[2]);
            else throw new IllegalArgumentException("Invalid probe mode");
        } catch (Exception error) {
            System.err.println("FAIL: synthetic result unknown; preserve marker, do not repeat send automatically");
            System.exit(1);
        }
    }
}
