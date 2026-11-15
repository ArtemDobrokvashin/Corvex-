package ru.corvex.mailstorage.james;

// Synthetic probe only, for the existing Corvex James container.
// send: exactly one message with a 25-level nested ZIP containing the EICAR
//       test string, submitted to the loopback-only synthetic SMTP listener.
// absent: proves the same message never reached the mailbox through James JMAP.
// The quarantine repository counter is read separately by the caller from
// PostgreSQL; this class never reads or changes user mail.
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

class JamesAvQuarantineProbe {
    private static final Path ADAPTER = Path.of("/run/james-adapter");
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private static final String EICAR =
            "X5O!P%@AP[4/PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    private static final int DEPTH = 25;
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

    /** DEPTH nested ZIP layers; only the innermost entry holds the test string. */
    static byte[] nestedArchive() throws IOException {
        byte[] payload = EICAR.getBytes(StandardCharsets.US_ASCII);
        for (int layer = 0; layer < DEPTH; layer++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("layer-" + layer + (layer == 0 ? ".bin" : ".zip")));
                zip.write(payload);
                zip.closeEntry();
            }
            payload = out.toByteArray();
        }
        return payload;
    }

    private static void send(String login, String marker) throws Exception {
        byte[] archive = nestedArchive();
        Properties properties = new Properties();
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "30000");
        properties.setProperty("mail.smtp.writetimeout", "30000");
        properties.setProperty("mail.smtp.auth", "false");
        properties.setProperty("mail.smtp.starttls.enable", "false");
        Session session = Session.getInstance(properties);
        session.setDebug(false);
        var message = new MimeMessage(session);
        message.setFrom(new InternetAddress("synthetic@localhost", true));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(login, true));
        message.setSubject("Corvex AV quarantine probe " + marker, "UTF-8");
        var text = new MimeBodyPart();
        text.setText("Synthetic quarantine probe; not user mail. Marker " + marker, "UTF-8");
        var attachment = new MimeBodyPart();
        attachment.setDataHandler(new DataHandler(new ByteArrayDataSource(archive, "application/zip")));
        attachment.setFileName("layer-" + DEPTH + ".zip");
        var body = new MimeMultipart("mixed");
        body.addBodyPart(text);
        body.addBodyPart(attachment);
        message.setContent(body);
        message.saveChanges();
        try (var transport = session.getTransport("smtp")) {
            // Fixed loopback endpoint: this probe cannot send to another host.
            transport.connect("127.0.0.1", 2525, null, null);
            transport.sendMessage(message, message.getAllRecipients());
        }
        System.out.println("PASS: one synthetic message with a " + DEPTH + "-level archive accepted by local James SMTP");
    }

    /** Opens the JMAP session only: proves the synthetic account exists. */
    private static void check(String login) throws Exception {
        var client = client();
        var session = client.session(token(login));
        var primary = session.path("primaryAccounts").path(MAIL);
        if (!primary.isTextual() || primary.textValue().isBlank()) throw new IllegalStateException("No mail account for the synthetic login");
        System.out.println("PASS: synthetic account exists and is readable via JMAP");
    }

    private static JamesJmapReadClient client() throws Exception {
        return new JamesJmapReadClient(URI.create("https://james-jmap:8443"), mapper, jmapTls());
    }

    private static String token(String login) throws Exception {
        UUID owner = UUID.randomUUID();
        var principal = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
        var mapping = new JamesMailboxBinding.ProvisionedMailbox(owner, true, login, "synthetic-only");
        return new JamesReadTokenIssuer(signingKey(), Clock.systemUTC(), mapper).issue(principal, mapping);
    }

    private static void absent(String login, String marker) throws Exception {
        var client = client();
        String token = token(login);
        var primary = client.session(token).path("primaryAccounts").path(MAIL);
        if (!primary.isTextual() || primary.textValue().isBlank()) throw new IllegalStateException("Invalid session");
        String account = primary.textValue();
        String subject = "Corvex AV quarantine probe " + marker;
        for (int attempt = 0; attempt < 20; attempt++) {
            var query = mapper.createObjectNode().put("accountId", account).put("limit", 100).put("position", 0);
            query.putObject("filter").put("subject", subject);
            var response = client.read(token, "Email/query", query);
            var ids = response.path("ids");
            if (!account.equals(response.path("accountId").asText()) || !ids.isArray()) {
                throw new IllegalStateException("Invalid query");
            }
            if (!ids.isEmpty()) {
                var request = mapper.createObjectNode().put("accountId", account);
                request.set("ids", ids.deepCopy());
                request.putArray("properties").add("id").add("subject");
                var messages = client.read(token, "Email/get", request);
                for (var item : messages.path("list")) {
                    if (subject.equals(item.path("subject").asText())) {
                        throw new IllegalStateException("Message reached the mailbox; AV gate did not stop it");
                    }
                }
            }
            Thread.sleep(1000);
        }
        System.out.println("PASS: synthetic nested-archive message never appeared in the mailbox");
    }

    public static void main(String[] args) {
        try {
            if (args.length < 2 || !args[1].matches("cvx-[0-9a-f]{32}@localhost")) {
                throw new IllegalArgumentException("Invalid synthetic arguments");
            }
            if ("check".equals(args[0]) && args.length == 2) {
                check(args[1]);
                return;
            }
            if (args.length != 3 || !args[2].matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid synthetic arguments");
            if ("send".equals(args[0])) send(args[1], args[2]);
            else if ("absent".equals(args[0])) absent(args[1], args[2]);
            else throw new IllegalArgumentException("Invalid probe mode");
        } catch (Exception error) {
            System.err.println("FAIL: " + error.getMessage() + "; preserve the marker, do not repeat the send automatically");
            System.exit(1);
        }
    }
}
