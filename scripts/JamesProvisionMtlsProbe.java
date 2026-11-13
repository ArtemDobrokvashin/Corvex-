// Transport probe from the existing mail-storage container. Sends one empty,
// unauthenticated PUT to a synthetic login solely to verify WebAdmin rejects it.
// James requires a non-null password in the JSON body to create a user.
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

class JamesProvisionMtlsProbe {
    private static final String ORIGIN = "https://james-admin:8443";
    private static final String USER_PATH = "/users/cvx-00000000000000000000000000000000@localhost";

    private static SSLContext context(boolean clientIdentity) throws Exception {
        var trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        try (var in = Files.newInputStream(Path.of("/run/james-adapter/admin-trust.crt"))) {
            trust.setCertificateEntry("server", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        var trustFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustFactory.init(trust);
        javax.net.ssl.KeyManager[] managers = null;
        if (clientIdentity) {
            char[] password = Files.readString(Path.of("/run/james-adapter/admin-client-password"), StandardCharsets.US_ASCII).trim().toCharArray();
            try {
                var keys = KeyStore.getInstance("PKCS12");
                try (var in = Files.newInputStream(Path.of("/run/james-adapter/admin-client.p12"))) { keys.load(in, password); }
                var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(keys, password);
                managers = factory.getKeyManagers();
            } finally { java.util.Arrays.fill(password, '\0'); }
        }
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(managers, trustFactory.getTrustManagers(), null);
        return ssl;
    }
    private static HttpClient client(boolean identity) throws Exception {
        return HttpClient.newBuilder().sslContext(context(identity)).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static int status(HttpClient client, String method, String path) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(ORIGIN + path)).timeout(Duration.ofSeconds(10));
        var request = (method.equals("GET") ? b.GET() : b.method(method, HttpRequest.BodyPublishers.noBody())).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var ignored = response.body()) { return response.statusCode(); }
    }
    private static void expect(HttpClient client, String method, String path, int expected) throws Exception {
        int actual = status(client, method, path);
        if (actual != expected) throw new IllegalStateException(method + " " + path + " => " + actual + ", expected " + expected);
        System.out.println("PASS: " + method + " " + path + " => " + actual);
    }
    private static boolean sslError(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) if (e instanceof SSLException) return true;
        return false;
    }
    public static void main(String[] args) throws Exception {
        var trusted = client(true);
        expect(trusted, "GET", "/jmap/session", 404);
        expect(trusted, "GET", USER_PATH, 403);
        expect(trusted, "GET", USER_PATH + "?force=true", 400);
        expect(trusted, "PUT", "/not-a-user", 404);
        // No Password header and empty body: no valid create payload exists.
        expect(trusted, "PUT", USER_PATH, 401);
        try {
            int result = status(client(false), "GET", "/not-a-user");
            if (result != 400 && result != 403) throw new IllegalStateException("Missing client certificate unexpectedly accepted: " + result);
        } catch (java.io.IOException error) {
            if (!sslError(error)) throw error;
        }
        System.out.println("PASS: connection without client certificate rejected");
        System.out.println("PASS: mTLS and route probe only; no user was created or mail switched");
    }
}
