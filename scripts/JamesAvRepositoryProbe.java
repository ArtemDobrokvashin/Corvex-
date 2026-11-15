import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/** Read-only WebAdmin observation from inside James. Never prints credentials or other mail. */
public final class JamesAvRepositoryProbe {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final URI BASE = URI.create("http://127.0.0.1:8000");
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static HttpResponse<InputStream> get(String path, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(BASE.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Password", password).GET().build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static byte[] read(HttpResponse<InputStream> response) throws Exception {
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IllegalStateException("Response too large");
            return bytes;
        }
    }

    public static void main(String[] args) {
        try {
            if (!(args.length == 1 && args[0].equals("preflight")) &&
                    !(args.length == 3 && args[0].equals("observe") &&
                    args[1].matches("[A-Za-z0-9._~+/=-]{1,1024}") && args[2].matches("cvx-[0-9a-f]{32}@localhost"))) {
                throw new IllegalArgumentException("Invalid arguments");
            }
            Properties config = new Properties();
            try (InputStream in = Files.newInputStream(Path.of("/root/conf/webadmin.properties"))) { config.load(in); }
            String password = config.getProperty("password");
            if (password == null || password.isBlank() || password.length() > 2048 || password.contains("\r") || password.contains("\n")) {
                throw new IllegalStateException("WebAdmin password unavailable");
            }
            if (args[0].equals("preflight")) {
                HttpResponse<InputStream> response = get("/mailRepositories", password);
                byte[] data = read(response);
                if (response.statusCode() != 200 || !MAPPER.readTree(data).isArray()) throw new IllegalStateException("Repository API unavailable");
                System.out.println("PASS: read-only James repository API reachable (no repository contents printed)");
                return;
            }
            String path = "/mailRepositories/" + enc("var/mail/corvex-av-infected") + "/mails/" + enc(args[1]);
            for (int attempt = 0; attempt < 30; attempt++) {
                HttpResponse<InputStream> response = get(path, password);
                byte[] data = read(response);
                if (response.statusCode() == 200) {
                    JsonNode entry = MAPPER.readTree(data);
                    if (!args[1].equals(entry.path("name").asText()) || !args[2].equals(entry.path("sender").asText())) {
                        throw new IllegalStateException("Quarantine identity mismatch");
                    }
                    JsonNode recipients = entry.path("recipients");
                    if (!recipients.isArray() || recipients.size() != 1 || !args[2].equals(recipients.get(0).asText())) {
                        throw new IllegalStateException("Quarantine recipient mismatch");
                    }
                    System.out.println("PASS: exact synthetic JMAP submission observed in James infected repository");
                    System.out.println("NOT TESTED: mailbox non-delivery, real clamd outage, crash recovery, nested archive completeness or JMAP/IMAP bypass");
                    return;
                }
                if (response.statusCode() != 404) throw new IllegalStateException("Repository query failed");
                Thread.sleep(2000);
            }
            throw new IllegalStateException("Synthetic message not observed in quarantine");
        } catch (Exception error) {
            System.err.println("INCONCLUSIVE or FAIL: repository observation; no mail was deleted or replayed.");
            System.exit(2);
        }
    }
}
