package ru.corvex.mailsmtp.server;

import org.subethamail.smtp.server.SSLSocketCreator;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * PEM TLS для SubEtha: библиотека по умолчанию ждёт JVM keystore, а Corvex хранит сертификаты
 * как fullchain.pem/privkey.pem (как nginx). Здесь PEM превращается в SSLContext в памяти,
 * а SSLSocketCreator на его основе передаётся в SMTPServer.Builder.
 */
final class SmtpTlsSupport {

    private SmtpTlsSupport() {
    }

    static Optional<SSLContext> buildContext(String certChainFile, String privateKeyFile) {
        try {
            Path certPath = Path.of(certChainFile);
            Path keyPath = Path.of(privateKeyFile);
            if (!Files.isRegularFile(certPath) || !Files.isRegularFile(keyPath)) {
                return Optional.empty();
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certs = new ArrayList<>();
            try (InputStream in = Files.newInputStream(certPath)) {
                Collection<? extends Certificate> parsed = cf.generateCertificates(in);
                for (Certificate c : parsed) {
                    certs.add((X509Certificate) c);
                }
            }
            if (certs.isEmpty()) {
                return Optional.empty();
            }
            PrivateKey key = loadPkcs8PrivateKey(keyPath);
            char[] password = "corvex-ephemeral".toCharArray();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            keyStore.setKeyEntry("smtp", key, password, certs.toArray(new Certificate[0]));
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return Optional.of(context);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось собрать TLS-контекст SMTP из PEM: " + e.getMessage(), e);
        }
    }

    private static PrivateKey loadPkcs8PrivateKey(Path path) throws Exception {
        String pem = Files.readString(path);
        String base64 = pem
                .replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception rsaFailure) {
            return KeyFactory.getInstance("EC").generatePrivate(spec);
        }
    }

    static SSLSocketCreator socketCreator(SSLContext sslContext) {
        return socket -> {
            SSLSocketFactory sf = sslContext.getSocketFactory();
            InetSocketAddress remoteAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
            SSLSocket ssl = (SSLSocket) sf.createSocket(socket, remoteAddress.getHostString(), socket.getPort(), true);
            ssl.setUseClientMode(false);
            List<String> supported = List.of(ssl.getSupportedProtocols());
            List<String> enabled = new ArrayList<>();
            if (supported.contains("TLSv1.3")) {
                enabled.add("TLSv1.3");
            }
            if (supported.contains("TLSv1.2")) {
                enabled.add("TLSv1.2");
            }
            if (!enabled.isEmpty()) {
                ssl.setEnabledProtocols(enabled.toArray(new String[0]));
            }
            return ssl;
        };
    }
}
