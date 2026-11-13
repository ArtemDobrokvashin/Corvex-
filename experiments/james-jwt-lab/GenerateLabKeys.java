// Synthetic local test keys only. Never use or commit for production.
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.Base64;

class GenerateLabKeys {
    static void writeNew(Path file, byte[] bytes) throws Exception {
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    static byte[] pem(String label, byte[] bytes) {
        String content = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes);
        return ("-----BEGIN " + label + "-----\n" + content + "\n-----END " + label + "-----\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected synthetic lab key directory");
        Path root = Path.of(args[0]);
        Path james = Files.createDirectory(root.resolve("james"));
        Path tls = Files.createDirectory(root.resolve("tls"));
        Path secrets = Files.createDirectory(root.resolve("private"));
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var jwt = generator.generateKeyPair();
        writeNew(james.resolve("jwt-public.pem"), pem("PUBLIC KEY", jwt.getPublic().getEncoded()));
        writeNew(secrets.resolve("jwt-private.der"), jwt.getPrivate().getEncoded());
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(root.resolve("tls-lab.p12"))) {
            store.load(input, "synthetic-lab-only".toCharArray());
        }
        var key = store.getKey("lab", "synthetic-lab-only".toCharArray());
        var cert = store.getCertificate("lab");
        if (key == null || cert == null) throw new IllegalStateException("Missing synthetic TLS key");
        writeNew(tls.resolve("tls-key.pem"), pem("PRIVATE KEY", key.getEncoded()));
        writeNew(tls.resolve("tls-cert.pem"), pem("CERTIFICATE", cert.getEncoded()));
    }
}
