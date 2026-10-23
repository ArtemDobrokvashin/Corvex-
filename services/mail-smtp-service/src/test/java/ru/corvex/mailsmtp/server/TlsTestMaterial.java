package ru.corvex.mailsmtp.server;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Генерация одноразовой RSA пары через JDK keytool и экспорт её в PEM-формат Corvex. */
final class TlsTestMaterial {

    record Material(Path certificatePem, Path privateKeyPem, Certificate certificate, PrivateKey privateKey) {
    }

    private TlsTestMaterial() {
    }

    static Material generateRsa(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path keyStorePath = directory.resolve("smtp-test.p12");
        char[] password = "changeit".toCharArray();
        String alias = "smtp-test";

        Process process = new ProcessBuilder(
                keytool().toString(),
                "-genkeypair",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-keystore", keyStorePath.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("keytool timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + new String(output, StandardCharsets.UTF_8));
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keyStorePath)) {
            keyStore.load(in, password);
        }
        PrivateKey key = (PrivateKey) keyStore.getKey(alias, password);
        Certificate[] chain = keyStore.getCertificateChain(alias);

        StringBuilder certificates = new StringBuilder();
        for (Certificate certificate : chain) {
            certificates.append(pem("CERTIFICATE", certificate.getEncoded()));
        }
        Path certPem = directory.resolve("fullchain.pem");
        Path keyPem = directory.resolve("privkey.pem");
        Files.writeString(certPem, certificates.toString(), StandardCharsets.US_ASCII);
        Files.writeString(keyPem, pem("PRIVATE KEY", key.getEncoded()), StandardCharsets.US_ASCII);
        return new Material(certPem, keyPem, chain[0], key);
    }

    static String pem(String type, byte[] der) {
        String encoded = Base64.getMimeEncoder(64, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + type + "-----\r\n" + encoded + "\r\n-----END " + type + "-----\r\n";
    }

    private static Path keytool() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("keytool.exe");
        return Files.isRegularFile(windows) ? windows : bin.resolve("keytool");
    }
}
