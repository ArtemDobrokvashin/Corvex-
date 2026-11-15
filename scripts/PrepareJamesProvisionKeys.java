// Exports only newly generated laptop provisioning certificates. No Docker calls.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStore;
import java.util.Base64;

class PrepareJamesProvisionKeys {
    static void create(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
    static byte[] pem(String label, byte[] der) {
        String data = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return ("-----BEGIN " + label + "-----\n" + data + "\n-----END " + label + "-----\n").getBytes(StandardCharsets.US_ASCII);
    }
    static KeyStore load(Path path, char[] password) throws Exception {
        var store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(path)) { store.load(input, password); }
        return store;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected existing new admin directory and adapter directory");
        String serverPassword = System.getenv("CORVEX_JAMES_ADMIN_SERVER_PASSWORD");
        String clientPassword = System.getenv("CORVEX_JAMES_ADMIN_CLIENT_PASSWORD");
        if (serverPassword == null || clientPassword == null || serverPassword.isBlank() || clientPassword.isBlank()) {
            throw new IllegalArgumentException("Missing ephemeral key passwords");
        }
        Path root = Path.of(args[0]).toAbsolutePath(), adapter = Path.of(args[1]).toAbsolutePath();
        if (!Files.isDirectory(root) || !Files.isDirectory(adapter)) throw new IllegalArgumentException("Invalid configuration directory");
        char[] serverPass = serverPassword.toCharArray(), clientPass = clientPassword.toCharArray();
        try {
            var server = load(root.resolve("admin-server.p12"), serverPass);
            Path clientStore = root.resolve("admin-client-generated.p12");
            var client = load(clientStore, clientPass);
            var key = server.getKey("server", serverPass);
            var serverCert = server.getCertificate("server");
            var clientCert = client.getCertificate("client");
            if (key == null || serverCert == null || clientCert == null || !client.isKeyEntry("client")) {
                throw new IllegalStateException("Missing expected certificate or key");
            }
            create(adapter.resolve("admin-client.p12"), Files.readAllBytes(clientStore));
            byte[] serverPem = pem("CERTIFICATE", serverCert.getEncoded());
            create(root.resolve("admin.crt"), serverPem);
            create(root.resolve("admin.key"), pem("PRIVATE KEY", key.getEncoded()));
            create(root.resolve("client-ca.crt"), pem("CERTIFICATE", clientCert.getEncoded()));
            create(adapter.resolve("admin-trust.crt"), serverPem);
        } finally {
            java.util.Arrays.fill(serverPass, '\0');
            java.util.Arrays.fill(clientPass, '\0');
        }
    }
}
