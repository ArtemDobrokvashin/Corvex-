package ru.corvex.mailsmtp.outbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimKeyRingTest {

    @TempDir
    Path dir;

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private Path pem(String name, PrivateKey key) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        return file;
    }

    private static String expectedPublicKey(PrivateKey key) throws Exception {
        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) key;
        PublicKey publicKey = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    @Test
    void loadsOneKeyPerDomainFromDirectory() throws Exception {
        KeyPair first = rsaKeyPair();
        KeyPair second = rsaKeyPair();
        pem("company.local.pem", first.getPrivate());
        pem("branch.example.pem", second.getPrivate());
        Files.writeString(dir.resolve("branch.example.selector"), "branch2\n");

        DkimKeyRing ring = DkimKeyRing.load("", "corvex1", null, dir.toString());

        assertEquals(2, ring.keys().size());
        assertEquals("corvex1", ring.find("company.local").orElseThrow().selector());
        assertEquals("branch2", ring.find("branch.example").orElseThrow().selector());
        assertEquals(expectedPublicKey(first.getPrivate()),
                ring.find("COMPANY.LOCAL").orElseThrow().publicKeyBase64());
        assertEquals(List.of("branch.example", "company.local"),
                ring.keys().stream().map(DkimKeyRing.Key::domain).toList());
    }

    @Test
    void skipsFilesThatAreNotDomainsAndKeepsTheRest() throws Exception {
        KeyPair key = rsaKeyPair();
        pem("company.local.pem", key.getPrivate());
        Files.writeString(dir.resolve("notes.pem"), "not a key");

        DkimKeyRing ring = DkimKeyRing.load("", "corvex1", null, dir.toString());

        assertEquals(1, ring.keys().size());
        assertTrue(ring.contains("company.local"));
        assertFalse(ring.contains("notes"));
    }

    @Test
    void unreadableKeyDoesNotDisableOtherDomains() throws Exception {
        KeyPair key = rsaKeyPair();
        pem("company.local.pem", key.getPrivate());
        Files.writeString(dir.resolve("broken.example.pem"), "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n");

        DkimKeyRing ring = DkimKeyRing.load("", "corvex1", null, dir.toString());

        assertEquals(1, ring.keys().size());
        assertFalse(ring.contains("broken.example"));
    }

    @Test
    void explicitKeyWinsOverDirectoryDuplicate() throws Exception {
        KeyPair explicit = rsaKeyPair();
        KeyPair duplicate = rsaKeyPair();
        Path explicitFile = pem("explicit.pem", explicit.getPrivate());
        Files.writeString(dir.resolve("company.local.pem"), Files.readString(pem("d.pem", duplicate.getPrivate())));

        DkimKeyRing ring = DkimKeyRing.load("company.local", "corvex1", explicitFile.toString(), dir.toString());

        assertEquals(1, ring.keys().size());
        assertEquals(expectedPublicKey(explicit.getPrivate()),
                ring.find("company.local").orElseThrow().publicKeyBase64());
    }

    @Test
    void emptyRingIsNotEnabled() {
        DkimKeyRing ring = DkimKeyRing.load("company.local", "corvex1", dir.resolve("missing.pem").toString(), null);

        assertTrue(ring.isEmpty());
        assertNull(ring.find("company.local").orElse(null));
    }

    @Test
    void selectsKeyByFromDomainAndVerifiesSignature() throws Exception {
        KeyPair company = rsaKeyPair();
        KeyPair branch = rsaKeyPair();
        pem("company.local.pem", company.getPrivate());
        pem("branch.example.pem", branch.getPrivate());
        DkimSigner signer = new DkimSigner(DkimKeyRing.load("", "corvex1", null, dir.toString()));

        byte[] branchMessage = ("From: Alice <alice@branch.example>\r\n"
                + "To: bob@partner.example\r\n"
                + "Subject: Branch\r\n"
                + "Date: Mon, 19 Oct 2026 10:00:00 +0000\r\n"
                + "\r\n"
                + "Hello\r\n").getBytes(StandardCharsets.ISO_8859_1);
        byte[] companyMessage = ("From: Alice <alice@company.local>\r\n"
                + "To: bob@partner.example\r\n"
                + "Subject: Company\r\n"
                + "Date: Mon, 19 Oct 2026 10:00:00 +0000\r\n"
                + "\r\n"
                + "Hello\r\n").getBytes(StandardCharsets.ISO_8859_1);

        String signedBranch = new String(signer.signIfConfigured(branchMessage, "alice@branch.example"),
                StandardCharsets.ISO_8859_1);
        String signedCompany = new String(signer.signIfConfigured(companyMessage, "alice@company.local"),
                StandardCharsets.ISO_8859_1);

        assertTrue(signedBranch.contains("d=branch.example"));
        assertTrue(signedCompany.contains("d=company.local"));
        assertTrue(verify(signedBranch, branch.getPrivate()));
        assertTrue(verify(signedCompany, company.getPrivate()));
    }

    @Test
    void domainWithoutKeyIsNotSignedWithAnotherDomainKey() throws Exception {
        KeyPair company = rsaKeyPair();
        pem("company.local.pem", company.getPrivate());
        DkimSigner signer = new DkimSigner(DkimKeyRing.load("", "corvex1", null, dir.toString()));
        byte[] raw = ("From: Alice <alice@other.example>\r\n"
                + "To: bob@partner.example\r\n"
                + "Subject: Other\r\n"
                + "\r\n"
                + "Hello\r\n").getBytes(StandardCharsets.ISO_8859_1);

        byte[] result = signer.signIfConfigured(raw, "alice@other.example");

        assertArrayEquals(raw, result);
    }

    /** Заголовок подписи разворачивается целиком: b= разрезан на строки по RFC 5322. */
    private static String unfoldDkimHeader(String message) {
        int end = -1;
        for (int i = message.indexOf("\r\n"); i >= 0; i = message.indexOf("\r\n", i + 2)) {
            if (i + 2 >= message.length()
                    || (message.charAt(i + 2) != ' ' && message.charAt(i + 2) != '\t')) {
                end = i;
                break;
            }
        }
        return message.substring("DKIM-Signature: ".length(), end)
                .replace("\r\n\t", "")
                .replace("\r\n ", "");
    }

    /** Проверка подписи настоящим публичным ключом: тест не доверяет наличию заголовка. */
    private static boolean verify(String message, PrivateKey privateKey) throws Exception {
        String unfolded = unfoldDkimHeader(message);
        int bIndex = unfolded.lastIndexOf("; b=");
        // "; b=" — ровно 4 символа. Раньше здесь стояло +5: первый символ base64
        // попадал в подписываемые данные, а в декодер уходила строка без него,
        // поэтому проверка падала на «Last unit does not have enough valid bits».
        String withoutSignature = unfolded.substring(0, bIndex + 4);
        String signatureValue = unfolded.substring(bIndex + 4).replaceAll("\\s+", "");
        String body = message.substring(message.indexOf("\r\n\r\n") + 4);
        String bh = unfolded.replaceAll(".*bh=([^;]+).*", "$1");

        String canonicalBody = body.replaceAll("\\s+", " ").trim();
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((canonicalBody + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        if (!Base64.getEncoder().encodeToString(digest).equals(bh)) {
            return false;
        }

        StringBuilder signedData = new StringBuilder();
        for (String name : List.of("from", "to", "subject", "date")) {
            for (String line : message.split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith(name + ":")) {
                    signedData.append(name).append(':')
                            .append(line.substring(name.length() + 1).trim()
                                    .replaceAll("[ \\t]+", " ").replaceAll(" +$", ""))
                            .append("\r\n");
                }
            }
        }
        signedData.append("dkim-signature:").append(withoutSignature.replaceAll("\\s+", " "));

        RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) privateKey;
        PublicKey publicKey = java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(signedData.toString().getBytes(StandardCharsets.ISO_8859_1));
        return verifier.verify(Base64.getDecoder().decode(signatureValue));
    }
}
