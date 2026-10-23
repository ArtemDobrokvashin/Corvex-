package ru.corvex.mailsmtp.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.subethamail.smtp.server.SSLSocketCreator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpTlsSupportTest {

    private static Path directory;
    private static TlsTestMaterial.Material material;

    @BeforeAll
    static void generateMaterial() throws Exception {
        directory = Files.createTempDirectory("corvex-smtp-tls-test");
        material = TlsTestMaterial.generateRsa(directory);
    }

    @AfterAll
    static void deleteMaterial() throws IOException {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void privateConstructorExistsForStaticUtility() throws Exception {
        Constructor<SmtpTlsSupport> constructor = SmtpTlsSupport.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }

    @Test
    void missingCertificateOrKeyReturnsEmpty() {
        assertTrue(SmtpTlsSupport.buildContext(
                directory.resolve("missing-cert.pem").toString(), material.privateKeyPem().toString()).isEmpty());
        assertTrue(SmtpTlsSupport.buildContext(
                material.certificatePem().toString(), directory.resolve("missing-key.pem").toString()).isEmpty());
        assertTrue(SmtpTlsSupport.buildContext(
                directory.toString(), material.privateKeyPem().toString()).isEmpty());
    }

    @Test
    void emptyCertificateFileReturnsEmpty() throws IOException {
        Path emptyCert = directory.resolve("empty-fullchain.pem");
        Files.writeString(emptyCert, "", StandardCharsets.US_ASCII);

        assertTrue(SmtpTlsSupport.buildContext(emptyCert.toString(), material.privateKeyPem().toString()).isEmpty());
    }

    @Test
    void malformedCertificateOrKeyThrowsIllegalState() throws IOException {
        Path badCert = directory.resolve("bad-fullchain.pem");
        Files.writeString(badCert,
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----", StandardCharsets.US_ASCII);
        IllegalStateException certError = assertThrows(IllegalStateException.class,
                () -> SmtpTlsSupport.buildContext(badCert.toString(), material.privateKeyPem().toString()));
        assertTrue(certError.getMessage().contains("TLS-контекст SMTP"));

        Path badKey = directory.resolve("bad-privkey.pem");
        Files.writeString(badKey, "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----", StandardCharsets.US_ASCII);
        IllegalStateException keyError = assertThrows(IllegalStateException.class,
                () -> SmtpTlsSupport.buildContext(material.certificatePem().toString(), badKey.toString()));
        assertTrue(keyError.getMessage().contains("TLS-контекст SMTP"));
    }

    @Test
    void buildsContextFromRsaPemPair() {
        Optional<SSLContext> context = SmtpTlsSupport.buildContext(
                material.certificatePem().toString(), material.privateKeyPem().toString());

        assertTrue(context.isPresent());
        assertEquals("TLS", context.orElseThrow().getProtocol());
    }

    @Test
    void ecPkcs8KeyUsesFallbackKeyFactory() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair ecPair = generator.generateKeyPair();
        Path ecKey = directory.resolve("ec-privkey.pem");
        Files.writeString(ecKey, TlsTestMaterial.pem("PRIVATE KEY", ecPair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);

        Optional<SSLContext> context = SmtpTlsSupport.buildContext(material.certificatePem().toString(), ecKey.toString());

        assertTrue(context.isPresent());
    }

    @Test
    void socketCreatorProducesServerSocketRestrictedToTls12And13() throws Exception {
        SSLContext serverContext = SmtpTlsSupport.buildContext(
                material.certificatePem().toString(), material.privateKeyPem().toString()).orElseThrow();
        SSLContext clientContext = trustingContext();
        SSLSocketCreator creator = SmtpTlsSupport.socketCreator(serverContext);

        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<SSLSocket> serverFuture = executor.submit(() -> {
                    Socket accepted = listener.accept();
                    SSLSocket ssl = creator.createSSLSocket(accepted);
                    ssl.startHandshake();
                    int value = ssl.getInputStream().read();
                    ssl.getOutputStream().write(value + 1);
                    ssl.getOutputStream().flush();
                    return ssl;
                });

                SSLSocket serverSocket = null;
                try (Socket plain = new Socket("127.0.0.1", listener.getLocalPort());
                     SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                             .createSocket(plain, "localhost", plain.getPort(), true)) {
                    client.startHandshake();
                    client.getOutputStream().write(41);
                    client.getOutputStream().flush();
                    assertEquals(42, client.getInputStream().read());

                    serverSocket = serverFuture.get(5, TimeUnit.SECONDS);
                    assertFalse(serverSocket.getUseClientMode());
                    assertTrue(Set.of("TLSv1.2", "TLSv1.3").containsAll(List.of(serverSocket.getEnabledProtocols())));
                    assertTrue(List.of(serverSocket.getEnabledProtocols()).contains(client.getSession().getProtocol()));
                } finally {
                    if (serverSocket != null) {
                        serverSocket.close();
                    }
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static SSLContext trustingContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("smtp-test", material.certificate());
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }
}
