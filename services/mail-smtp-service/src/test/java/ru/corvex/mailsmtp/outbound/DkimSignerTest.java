package ru.corvex.mailsmtp.outbound;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DkimSignerTest {

    @Test
    void relaxedBodyCanonicalization() {
        byte[] canonical = DkimSigner.canonicalBody("Hello  \t world \r\n\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        assertEquals("Hello world\r\n", new String(canonical, StandardCharsets.ISO_8859_1));
    }

    @Test
    void relaxedHeaderCanonicalization() {
        assertEquals("subject:A B", DkimSigner.canonicalHeader("Subject", "  A\r\n\t B  "));
    }

    @Test
    void signsMessageWithDkimHeader() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("RSA");
        keyPair.initialize(2048);
        DkimSigner signer = new DkimSigner("company.local", "corvex1", keyPair.generateKeyPair().getPrivate());
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
    }
}
