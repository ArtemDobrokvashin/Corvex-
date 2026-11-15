// Dedicated short-lived token for the disposable synthetic James lab only.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

class GenerateImportToken {
    static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    public static void main(String[] args) throws Exception {
        if (!"synthetic-only".equals(System.getenv("JAMES_LIVE_CONFIRM")) || args.length != 1)
            throw new IllegalArgumentException("Synthetic lab confirmation and directory required");
        Path root = Path.of(args[0]);
        var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(
                Files.readAllBytes(root.resolve("private/jwt-private.der"))));
        long now = Instant.now().getEpochSecond();
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.US_ASCII));
        String body = b64(("{\"sub\":\"user01@james.local\",\"iat\":" + now + ",\"exp\":" + (now + 180) + ",\"iss\":\"corvex-james-adapter\",\"aud\":\"james-jmap\"}").getBytes(StandardCharsets.US_ASCII));
        String input = header + "." + body;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(input.getBytes(StandardCharsets.US_ASCII));
        Files.writeString(root.resolve("export/import-token.txt"), input + "." + b64(signature.sign()),
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
    }
}
