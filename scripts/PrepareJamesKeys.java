// Creates dedicated keys for the James transition; never writes existing files.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.util.Base64;

class PrepareJamesKeys {
    static void create(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    static byte[] pem(String label, byte[] der) {
        String data = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return ("-----BEGIN " + label + "-----\n" + data + "\n-----END " + label + "-----\n").getBytes(StandardCharsets.US_ASCII);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected new configuration directory and empty adapter directory");
        String password = System.getenv("CORVEX_JAMES_PREPARE_KEYSTORE_PASSWORD");
        if (password == null || password.isBlank()) throw new IllegalArgumentException("Missing ephemeral keystore password");
        Path root = Path.of(args[0]).toAbsolutePath();
        Path adapter = Path.of(args[1]).toAbsolutePath();
        if (!Files.isDirectory(root) || !Files.isDirectory(adapter)) throw new IllegalStateException("Invalid configuration directories");
        try (var entries = Files.list(adapter)) {
            if (entries.findAny().isPresent()) throw new IllegalStateException("Adapter directory is not empty");
        }
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(root.resolve("jmap-keystore.p12"))) { store.load(input, password.toCharArray()); }
        var tlsKey = store.getKey("jmap", password.toCharArray());
        var tlsCert = store.getCertificate("jmap");
        if (tlsKey == null || tlsCert == null) throw new IllegalStateException("Missing TLS identity");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        var jwt = generator.generateKeyPair();
        create(root.resolve("jmap.crt"), pem("CERTIFICATE", tlsCert.getEncoded()));
        create(root.resolve("jmap.key"), pem("PRIVATE KEY", tlsKey.getEncoded()));
        create(root.resolve("jwt_publickey"), pem("PUBLIC KEY", jwt.getPublic().getEncoded()));
        create(adapter.resolve("jwt-private.pem"), pem("PRIVATE KEY", jwt.getPrivate().getEncoded()));
        create(adapter.resolve("jmap-trust.crt"), pem("CERTIFICATE", tlsCert.getEncoded()));
    }
}
