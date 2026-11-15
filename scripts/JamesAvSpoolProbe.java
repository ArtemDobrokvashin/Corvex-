package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** One-shot synthetic LOCAL-ONLY JMAP submission. Never retries a mutation. */
public final class JamesAvSpoolProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CORE = "urn:ietf:params:jmap:core";
    private static final String MAIL = "urn:ietf:params:jmap:mail";
    private static final String SUBMISSION = "urn:ietf:params:jmap:submission";
    private static URI james;
    private static final Path ADAPTER = Path.of("/run/james-adapter");
    private static final int MAX_RESPONSE = 1024 * 1024;
    private static String stage = "validation";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static RSAPrivateKey key() throws Exception {
        String pem = Files.readString(ADAPTER.resolve("jwt-private.pem"), StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN PRIVATE KEY-----", end = "-----END PRIVATE KEY-----";
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw new IllegalStateException("Invalid signing key");
        byte[] der = Base64.getDecoder().decode(pem.substring(begin.length(), pem.length() - end.length()).replaceAll("\\s", ""));
        return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] body(HttpResponse<InputStream> response) throws Exception {
        try (InputStream in = response.body()) {
            byte[] data = in.readNBytes(MAX_RESPONSE + 1);
            if (data.length > MAX_RESPONSE) throw new IllegalStateException("Response too large");
            return data;
        }
    }

    private static HttpResponse<InputStream> send(HttpRequest request) throws Exception {
        // No redirects and no retries: a lost response is an uncertain mutation.
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static JsonNode json(HttpResponse<InputStream> response, int expected) throws Exception {
        byte[] bytes = body(response);
        if (response.statusCode() != expected) throw new IllegalStateException("Unexpected HTTP status");
        return MAPPER.readTree(bytes);
    }

    private static HttpRequest.Builder request(URI uri, String token) {
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json; jmapVersion=rfc-8621");
    }

    private static JsonNode call(String token, String method, ObjectNode arguments, boolean submission) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        var using = payload.putArray("using").add(CORE).add(MAIL);
        if (submission) using.add(SUBMISSION);
        var entry = payload.putArray("methodCalls").addArray();
        entry.add(method).add(arguments).add("corvex-av-test");
        HttpRequest req = request(james.resolve("/jmap"), token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(payload))).build();
        JsonNode root = json(send(req), 200);
        JsonNode responses = root.path("methodResponses");
        if (!responses.isArray() || responses.size() != 1 || !method.equals(responses.get(0).path(0).asText()) ||
                !"corvex-av-test".equals(responses.get(0).path(2).asText())) throw new IllegalStateException("JMAP method failed");
        return responses.get(0).path(1);
    }

    private static byte[] syntheticMime(String login, String runId) throws Exception {
        byte[] eicar = String.join("", "X5O!P%@AP[4\\PZX54(P^)7CC)7}$",
                "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*").getBytes(StandardCharsets.US_ASCII);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(login, true));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(login, true));
        message.setSubject("Corvex AV synthetic " + runId, "UTF-8");
        message.setHeader("X-Corvex-AV-Probe", runId);
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Synthetic antivirus acceptance sample. No external recipients.", "UTF-8");
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setDataHandler(new DataHandler(new ByteArrayDataSource(eicar, "application/octet-stream")));
        attachment.setFileName("corvex-eicar-test.txt");
        MimeMultipart parts = new MimeMultipart();
        parts.addBodyPart(text);
        parts.addBodyPart(attachment);
        message.setContent(parts);
        message.saveChanges();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            message.writeTo(output);
            byte[] bytes = output.toByteArray();
            if (bytes.length > 16384) throw new IllegalStateException("Synthetic MIME too large");
            return bytes;
        }
    }

    private static String requiredText(JsonNode node) {
        if (!node.isTextual() || !node.asText().matches("[A-Za-z0-9._~+/=-]{1,1024}")) throw new IllegalStateException("Required ID missing or invalid");
        return node.asText();
    }

    private static void journal(Path path, String text) throws Exception {
        if (!text.matches("[A-Za-z0-9._~+/= -]{1,1100}")) throw new IllegalArgumentException("Invalid journal entry");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.US_ASCII.encode(text + "\n");
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 4 || !args[0].matches("cvx-[0-9a-f]{32}@localhost") || !args[1].matches("[0-9a-f]{32}") ||
                    !args[2].equals("/tmp/corvex-james-spool-" + args[1] + "/journal.txt") ||
                    !args[3].matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
                throw new IllegalArgumentException("Synthetic account, marker and James IPv4 required");
            }
            String login = args[0], runId = args[1];
            Path journalPath = Path.of(args[2]);
            james = URI.create("http://" + args[3] + ":80");
            journal(journalPath, "intent " + runId);
            UUID owner = UUID.randomUUID();
            var principal = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
            var mapping = new JamesMailboxBinding.ProvisionedMailbox(owner, true, login, "synthetic-only");
            var issuer = new JamesReadTokenIssuer(key(), Clock.systemUTC(), MAPPER);
            String token = issuer.issue(principal, mapping);
            stage = "read-only prerequisites";
            JsonNode session = json(send(request(james.resolve("/jmap/session"), token).GET().build()), 200);
            String account = requiredText(session.path("primaryAccounts").path(MAIL));
            JsonNode folders = call(token, "Mailbox/get", MAPPER.createObjectNode().put("accountId", account), false).path("list");
            if (!folders.isArray()) throw new IllegalStateException("Mailbox list missing");
            String drafts = null;
            for (JsonNode folder : folders) {
                if ("drafts".equals(folder.path("role").asText())) {
                    if (drafts != null) throw new IllegalStateException("Ambiguous drafts");
                    drafts = requiredText(folder.path("id"));
                }
            }
            if (drafts == null) throw new IllegalStateException("Synthetic drafts mailbox missing");
            System.out.println("PASS: isolated synthetic account and drafts mailbox");
            stage = "synthetic blob upload (mutation, never retry)";
            byte[] mime = syntheticMime(login, runId);
            journal(journalPath, "upload intent");
            HttpRequest upload = request(james.resolve("/upload/" + enc(account)), token)
                    .header("Content-Type", "message/rfc822").POST(HttpRequest.BodyPublishers.ofByteArray(mime)).build();
            String blob = requiredText(json(send(upload), 201).path("blobId"));
            journal(journalPath, "upload acknowledged " + blob);
            System.out.println("PASS: synthetic MIME uploaded; one-shot marker must be retained");
            stage = "synthetic draft import (mutation, never retry)";
            journal(journalPath, "import intent");
            ObjectNode importArgs = MAPPER.createObjectNode().put("accountId", account);
            ObjectNode draft = importArgs.putObject("emails").putObject("avtest").put("blobId", blob);
            draft.putObject("mailboxIds").put(drafts, true);
            draft.putObject("keywords").put("$draft", true);
            String emailId = requiredText(call(token, "Email/import", importArgs, false).path("created").path("avtest").path("id"));
            journal(journalPath, "import acknowledged " + emailId);
            System.out.println("PASS: synthetic EICAR draft imported; no real user mailbox used");
            stage = "synthetic local submission (mutation, never retry)";
            journal(journalPath, "submission intent " + emailId);
            ObjectNode submitArgs = MAPPER.createObjectNode().put("accountId", account);
            ObjectNode submission = submitArgs.putObject("create").putObject("avtest").put("emailId", emailId);
            ObjectNode envelope = submission.putObject("envelope");
            envelope.putObject("mailFrom").put("email", login);
            envelope.putArray("rcptTo").addObject().put("email", login);
            String submissionId = requiredText(call(token, "EmailSubmission/set", submitArgs, true).path("created").path("avtest").path("id"));
            journal(journalPath, "submission acknowledged " + submissionId);
            System.out.println("PASS: synthetic local-only submission accepted by JMAP; delivery not yet established");
            System.out.println("Synthetic draft is retained; the matching repository check runs separately inside James.");
            System.out.println("Never replay or delete synthetic or existing mail automatically.");
        } catch (Exception error) {
            System.err.println("INCONCLUSIVE or FAIL at " + stage + "; preserve the one-shot marker; never resend automatically.");
            System.exit(2);
        }
    }
}
