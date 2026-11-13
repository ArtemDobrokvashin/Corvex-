// Read-only probe, executed inside the existing mail-storage container.
// No credentials, tokens, users, mailbox data or configuration are changed.
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

class JamesTransitionTlsProbe {
    private static final String HOST = "james-jmap";
    private static final int PORT = 8443;

    private static SSLContext context(boolean trustCertificate) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        if (trustCertificate) {
            try (InputStream in = Files.newInputStream(Path.of("/run/james-adapter/jmap-trust.crt"))) {
                store.setCertificateEntry("jmap", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
        }
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS");
        TrustManager[] trust = trustCertificate ? managers.getTrustManagers() : new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No trusted certificates");
            }
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No trusted certificates");
            }
        }};
        ssl.init(null, trust, null);
        return ssl;
    }

    private static void handshake(SSLContext context, String expectedName) throws Exception {
        try (var raw = new java.net.Socket()) {
            raw.connect(new InetSocketAddress(HOST, PORT), 5000);
            raw.setSoTimeout(5000);
            try (SSLSocket socket = (SSLSocket) context.getSocketFactory()
                    .createSocket(raw, expectedName, PORT, false)) {
                SSLParameters params = socket.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(params);
                socket.startHandshake();
            }
        }
    }

    private static void mustReject(SSLContext ssl, String hostname, String label) throws Exception {
        try {
            handshake(ssl, hostname);
        } catch (SSLHandshakeException expected) {
            System.out.println("PASS: " + label);
            return;
        }
        throw new IllegalStateException(label + " unexpectedly accepted");
    }

    private static void status(HttpClient client, String method, String path, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("https://" + HOST + ":" + PORT + path))
                .timeout(Duration.ofSeconds(15));
        HttpRequest request = (method.equals("GET") ? builder.GET()
                : builder.method(method, HttpRequest.BodyPublishers.noBody())).build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != expected) {
            throw new IllegalStateException(method + " " + path + " returned " + response.statusCode() + ", expected " + expected);
        }
        System.out.println("PASS: " + method + " " + path + " => " + expected);
    }

    public static void main(String[] args) throws Exception {
        SSLContext trusted = context(true);
        handshake(trusted, HOST);
        System.out.println("PASS: TLS trusted certificate and hostname");
        mustReject(context(false), HOST, "untrusted certificate rejected");
        mustReject(trusted, "not-james.invalid", "incorrect hostname rejected");
        var client = HttpClient.newBuilder().sslContext(trusted).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        status(client, "GET", "/jmap/session", 401);
        status(client, "GET", "/jmap", 403);
        status(client, "GET", "/not-jmap", 404);
        System.out.println("PASS: transport probe only; authenticated JMAP, provisioning and mail remain untested");
    }
}
