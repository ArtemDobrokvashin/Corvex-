package ru.corvex.mailsmtp.outbound;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimSignerTest {

    @TempDir
    Path tempDir;

    @Test
    void relaxedBodyCanonicalization() {
        byte[] canonical = DkimSigner.canonicalBody("Hello  \t world \r\n\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("Hello world\r\n", new String(canonical, StandardCharsets.ISO_8859_1));
    }

    @Test
    void relaxedBodyCanonicalizationKeepsInternalBlankLinesAndHandlesEmptyBodies() {
        assertArrayEquals(new byte[0], DkimSigner.canonicalBody(new byte[0]));
        assertArrayEquals(new byte[0], DkimSigner.canonicalBody("\r\n \t\r\n".getBytes(StandardCharsets.ISO_8859_1)));

        byte[] canonical = DkimSigner.canonicalBody("A \t B\r\n \r\nC\rD\n\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("A B\r\n\r\nC\r\nD\r\n", new String(canonical, StandardCharsets.ISO_8859_1));
    }

    @Test
    void relaxedHeaderCanonicalization() {
        assertEquals("subject:A B", DkimSigner.canonicalHeader("Subject", "  A\r\n\t B  "));
        assertEquals("x-test:one two three", DkimSigner.canonicalHeader(" X-Test ", " one\t\t two\r\n  three "));
    }

    @Test
    void signsMessageWithDkimHeader() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        DkimSigner signer = new DkimSigner("company.local", "corvex1", keyPair.getPrivate());
        byte[] raw = ("From: Alice <alice@company.local>\r\n"
                + "To: bob@partner.example\r\n"
                + "Subject: Hi\r\n"
                + "Date: Mon, 19 Oct 2026 10:00:00 +0000\r\n"
                + "Message-ID: <1@company.local>\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Hello\r\n").getBytes(StandardCharsets.ISO_8859_1);

        String signed = new String(signer.signIfConfigured(raw, "alice@company.local"), StandardCharsets.ISO_8859_1);

        assertTrue(signed.startsWith("DKIM-Signature: v=1; a=rsa-sha256; c=relaxed/relaxed; d=company.local; s=corvex1;"));
        assertTrue(signed.contains("h=from:to:subject:date:message-id:content-type; b="));
        assertTrue(signed.contains("\r\nFrom: Alice <alice@company.local>"));
        assertTrue(signed.contains("\r\n\t"), "2048-bit signature must be folded");
    }

    @Test
    void signatureVerifiesWithPublicKeyAndUsesLastDuplicateHeader() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        DkimSigner signer = new DkimSigner("company.local", "corvex1", keyPair.getPrivate());
        String body = "First  line\r\nSecond\r\n";
        byte[] raw = ("From: Alice <alice@company.local>\r\n"
                + "To: bob@partner.example\r\n"
                + "Subject: Old value\r\n"
                + "Subject: New\r\n\t value\r\n"
                + "Date: Mon, 19 Oct 2026 10:00:00 +0000\r\n"
                + "\r\n" + body).getBytes(StandardCharsets.ISO_8859_1);

        String signed = new String(signer.sign(raw), StandardCharsets.ISO_8859_1);
        Map<String, String> tags = dkimTags(signed);
        assertEquals("from:to:subject:date", tags.get("h"));
        String expectedBodyHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(DkimSigner.canonicalBody(body.getBytes(StandardCharsets.ISO_8859_1))));
        assertEquals(expectedBodyHash, tags.get("bh"));

        String dkimWithoutSignature = "v=1; a=rsa-sha256; c=relaxed/relaxed; d=company.local; s=corvex1; t="
                + tags.get("t") + "; bh=" + tags.get("bh") + "; h=" + tags.get("h") + "; b=";
        String data = DkimSigner.canonicalHeader("from", " Alice <alice@company.local>") + "\r\n"
                + DkimSigner.canonicalHeader("to", " bob@partner.example") + "\r\n"
                + DkimSigner.canonicalHeader("subject", " New\r\n\t value") + "\r\n"
                + DkimSigner.canonicalHeader("date", " Mon, 19 Oct 2026 10:00:00 +0000") + "\r\n"
                + DkimSigner.canonicalHeader("dkim-signature", dkimWithoutSignature);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(data.getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(verifier.verify(Base64.getDecoder().decode(tags.get("b"))));
    }

    @Test
    void disabledSignerReturnsOriginalBytes() {
        DkimSigner signer = new DkimSigner("company.local", "corvex1", (java.security.PrivateKey) null);
        byte[] raw = "From: alice@company.local\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);

        assertFalse(signer.isEnabled());
        assertSame(raw, signer.signIfConfigured(raw, "alice@company.local"));
    }

    @Test
    void publicConstructorTreatsMissingOrDirectoryKeyAsDisabledAndLoadsPkcs8Pem() throws Exception {
        DkimSigner missing = new DkimSigner("company.local", "corvex1", tempDir.resolve("absent.pem").toString());
        assertFalse(missing.isEnabled());

        DkimSigner directory = new DkimSigner("company.local", "corvex1", tempDir.toString());
        assertFalse(directory.isEnabled());

        KeyPair keyPair = rsaKeyPair();
        Path keyFile = tempDir.resolve("dkim.pem");
        Files.writeString(keyFile, pem(keyPair.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
        DkimSigner loaded = new DkimSigner(" Company.Local,ignored.example", "selector9", keyFile.toString());
        byte[] raw = "From: alice@company.local\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);

        String signed = new String(loaded.signIfConfigured(raw, "alice@company.local"), StandardCharsets.ISO_8859_1);
        assertTrue(loaded.isEnabled());
        assertTrue(signed.contains("d=company.local; s=selector9;"));
    }

    @Test
    void publicConstructorRejectsMalformedKeyFile() throws Exception {
        Path keyFile = tempDir.resolve("broken.pem");
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\nnot*base64\n-----END PRIVATE KEY-----", StandardCharsets.US_ASCII);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DkimSigner("company.local", "corvex1", keyFile.toString()));
        assertTrue(error.getMessage().contains("PKCS#8"));
    }

    @Test
    void skipsForeignFromDomainAndAlreadySignedDomain() throws Exception {
        DkimSigner signer = new DkimSigner("company.local", "corvex1", rsaKeyPair().getPrivate());
        byte[] foreign = "From: alice@other.example\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);
        assertSame(foreign, signer.signIfConfigured(foreign, "alice@other.example"));

        byte[] alreadySigned = ("DKIM-Signature: v=1; d=COMPANY.LOCAL; b=abc\r\n"
                + "From: alice@company.local\r\n\r\nbody").getBytes(StandardCharsets.ISO_8859_1);
        assertSame(alreadySigned, signer.signIfConfigured(alreadySigned, "alice@company.local"));

        byte[] otherSignature = ("DKIM-Signature: v=1; d=other.example; b=abc\r\n"
                + "From: alice@company.local\r\n\r\nbody").getBytes(StandardCharsets.ISO_8859_1);
        String resigned = new String(signer.signIfConfigured(otherSignature, "alice@company.local"), StandardCharsets.ISO_8859_1);
        assertTrue(resigned.startsWith("DKIM-Signature: v=1; a=rsa-sha256;"));
        assertTrue(resigned.contains("d=other.example; b=abc"));
    }

    @Test
    void usesEnvelopeDomainWhenFromHeaderMissingOrHasNoDomain() throws Exception {
        DkimSigner signer = new DkimSigner("company.local", "corvex1", rsaKeyPair().getPrivate());

        byte[] noFrom = "To: bob@example.org\r\nSubject: Hi\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);
        String signedNoFrom = new String(
                signer.signIfConfigured(noFrom, "Alice <ALICE@COMPANY.LOCAL>"), StandardCharsets.ISO_8859_1);
        assertTrue(signedNoFrom.startsWith("DKIM-Signature:"));

        byte[] invalidFrom = "From: Alice without address\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);
        String signedInvalidFrom = new String(
                signer.signIfConfigured(invalidFrom, "alice@company.local"), StandardCharsets.ISO_8859_1);
        assertTrue(signedInvalidFrom.startsWith("DKIM-Signature:"));

        assertSame(noFrom, signer.signIfConfigured(noFrom, null));
    }

    @Test
    void parsesLfOnlyHeadersNoSeparatorAndHeaderWithoutColon() throws Exception {
        DkimSigner signer = new DkimSigner("company.local", "corvex1", rsaKeyPair().getPrivate());

        byte[] lfOnly = "From: alice@company.local\nX-Broken-Header\nSubject: LF\n\nbody\n".getBytes(StandardCharsets.ISO_8859_1);
        String signedLf = new String(signer.signIfConfigured(lfOnly, "alice@company.local"), StandardCharsets.ISO_8859_1);
        assertTrue(signedLf.startsWith("DKIM-Signature:"));
        assertTrue(signedLf.endsWith(new String(lfOnly, StandardCharsets.ISO_8859_1)));

        byte[] headersOnly = "From: alice@company.local\r\nSubject: No body".getBytes(StandardCharsets.ISO_8859_1);
        String signedHeadersOnly = new String(
                signer.signIfConfigured(headersOnly, "alice@company.local"), StandardCharsets.ISO_8859_1);
        assertTrue(signedHeadersOnly.startsWith("DKIM-Signature:"));
    }

    @Test
    void preservesOriginalBytesAfterPrependedHeader() throws Exception {
        DkimSigner signer = new DkimSigner("company.local", "corvex1", rsaKeyPair().getPrivate());
        byte[] prefix = "From: alice@company.local\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[]{0x00, 0x01, (byte) 0xC3, (byte) 0xA9, '\r', '\n'};
        byte[] raw = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, raw, prefix.length, body.length);

        byte[] signed = signer.signIfConfigured(raw, "alice@company.local");

        assertArrayEquals(raw, Arrays.copyOfRange(signed, signed.length - raw.length, signed.length));
    }

    @Test
    void wrapsSigningFailureForNonRsaKey() throws Exception {
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        DkimSigner signer = new DkimSigner("company.local", "corvex1", ec.generateKeyPair().getPrivate());
        byte[] raw = "From: alice@company.local\r\n\r\nbody".getBytes(StandardCharsets.ISO_8859_1);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> signer.sign(raw));
        assertTrue(error.getMessage().contains("DKIM"));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String pem(byte[] der) {
        return "-----BEGIN PRIVATE KEY-----\r\n"
                + Base64.getMimeEncoder(64, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\r\n-----END PRIVATE KEY-----\r\n";
    }

    private static Map<String, String> dkimTags(String signedMessage) {
        int end = -1;
        for (int i = signedMessage.indexOf("\r\n"); i >= 0; i = signedMessage.indexOf("\r\n", i + 2)) {
            if (i + 2 >= signedMessage.length()
                    || (signedMessage.charAt(i + 2) != ' ' && signedMessage.charAt(i + 2) != '\t')) {
                end = i;
                break;
            }
        }
        String header = signedMessage.substring("DKIM-Signature:".length(), end)
                .replace("\r\n\t", "")
                .replace("\r\n ", "");
        Map<String, String> tags = new LinkedHashMap<>();
        for (String part : header.split(";")) {
            int equals = part.indexOf('=');
            if (equals > 0) {
                tags.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
            }
        }
        return tags;
    }
}
