package ru.corvex.mailstorage.mime;

import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import ru.corvex.mailstorage.api.dto.MailDtos;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutgoingMessageFactoryTest {

    private final OutgoingMessageFactory factory = new OutgoingMessageFactory();

    @Test
    void composesPlainTextAndPreservesStableMessageId() throws Exception {
        MailDtos.SendMessageRequest req = request(
                List.of("to@example.test"), List.of("cc@example.test"), null,
                "Plain body", null, null);

        OutgoingMessageFactory.ComposedMessage composed = factory.compose(
                "sender@company.local", "Sender Name", "<stable@company.local>",
                List.of("to@example.test"), List.of("cc@example.test"), req);
        MimeMessage parsed = parse(composed.eml());

        assertEquals("<stable@company.local>", composed.messageIdHeader());
        assertEquals("<stable@company.local>", parsed.getMessageID());
        assertEquals("Sender Name <sender@company.local>", parsed.getFrom()[0].toString());
        assertEquals("to@example.test", parsed.getRecipients(Message.RecipientType.TO)[0].toString());
        assertEquals("cc@example.test", parsed.getRecipients(Message.RecipientType.CC)[0].toString());
        assertTrue(parsed.getSubject() == null || parsed.getSubject().isEmpty());
        assertTrue(String.valueOf(parsed.getContent()).contains("Plain body"));
        assertEquals("Corvex", parsed.getHeader("X-Mailer")[0]);
    }

    @Test
    void composesHtmlAsMultipartAlternativeWhenNoAttachments() throws Exception {
        MailDtos.SendMessageRequest req = request(
                List.of(), List.of(), "HTML subject", "Text version", "<p>HTML version</p>", List.of());

        MimeMessage parsed = parse(factory.compose(
                "sender@company.local", null, "<html@company.local>", List.of(), List.of(), req).eml());

        assertEquals("HTML subject", parsed.getSubject());
        Multipart content = (Multipart) parsed.getContent();
        assertEquals(2, content.getCount());
        assertTrue(String.valueOf(content.getBodyPart(0).getContent()).contains("Text version"));
        assertTrue(String.valueOf(content.getBodyPart(1).getContent()).contains("HTML version"));
        assertTrue(parsed.getContentType().contains("multipart/alternative"));
    }

    @Test
    void composesMixedMessageWithAttachmentDefaults() throws Exception {
        String encoded = Base64.getEncoder().encodeToString("file".getBytes(StandardCharsets.UTF_8));
        MailDtos.SendMessageRequest req = request(
                List.of("to@example.test"), List.of(), "Files", null, " ",
                List.of(
                        new MailDtos.SendAttachment("report.txt", "text/plain", encoded),
                        new MailDtos.SendAttachment("", null, null)));

        MimeMessage parsed = parse(factory.compose(
                "sender@company.local", "", "<mixed@company.local>",
                List.of("to@example.test"), List.of(), req).eml());

        assertTrue(parsed.getContentType().contains("multipart/mixed"));
        Multipart mixed = (Multipart) parsed.getContent();
        assertEquals(3, mixed.getCount());
        assertEquals("report.txt", mixed.getBodyPart(1).getFileName());
        assertEquals("file", new String(mixed.getBodyPart(1).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("attachment", mixed.getBodyPart(2).getFileName());
        assertEquals(0, mixed.getBodyPart(2).getInputStream().readAllBytes().length);
        assertTrue(mixed.getBodyPart(2).getContentType().startsWith("application/octet-stream"));
    }

    @Test
    void attachmentsCanWrapAlternativeBody() throws Exception {
        MailDtos.SendMessageRequest req = request(
                List.of("to@example.test"), List.of(), "Both", "text", "<b>html</b>",
                List.of(new MailDtos.SendAttachment("a.bin", "application/octet-stream", "AA==")));

        MimeMessage parsed = parse(factory.compose(
                "sender@company.local", "Sender", "<both@company.local>",
                List.of("to@example.test"), List.of(), req).eml());
        Multipart mixed = (Multipart) parsed.getContent();
        Multipart alternative = (Multipart) mixed.getBodyPart(0).getContent();

        assertEquals(2, mixed.getCount());
        assertEquals(2, alternative.getCount());
        assertEquals("a.bin", mixed.getBodyPart(1).getFileName());
    }

    @Test
    void rejectsInvalidRecipientsAndInvalidBase64() {
        MailDtos.SendMessageRequest validBody = request(
                List.of("not an address"), List.of(), "x", "body", null, null);
        assertThrows(AddressException.class, () -> factory.compose(
                "sender@company.local", "Sender", "<bad@x>",
                List.of("not an address"), List.of(), validBody));

        MailDtos.SendMessageRequest badAttachment = request(
                List.of("to@example.test"), List.of(), "x", "body", null,
                List.of(new MailDtos.SendAttachment("a", "text/plain", "%%%not-base64%%%")));
        assertThrows(IllegalArgumentException.class, () -> factory.compose(
                "sender@company.local", "Sender", "<bad2@x>",
                List.of("to@example.test"), List.of(), badAttachment));
    }

    private static MailDtos.SendMessageRequest request(
            List<String> to,
            List<String> cc,
            String subject,
            String text,
            String html,
            List<MailDtos.SendAttachment> attachments) {
        return new MailDtos.SendMessageRequest(to, cc, List.of(), subject, text, html, attachments, true, null);
    }

    private static MimeMessage parse(byte[] eml) throws Exception {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml));
    }
}
