package ru.corvex.mailstorage.mime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.corvex.mailstorage.storage.MaildirStorage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MimeMailParserAdditionalTest {

    @TempDir
    Path tmp;

    private MimeMailParser parser;

    @BeforeEach
    void setUp() {
        parser = new MimeMailParser(new MaildirStorage(tmp.resolve("mail"), tmp.resolve("attachments")));
    }

    @Test
    void parsesPlainMessageAddressesEncodedSubjectAndSentDate() throws Exception {
        String eml = """
                From: =?UTF-8?B?0JDQvdC90LA=?= <anna@example.test>\r
                To: one@company.local, "Two User" <two@company.local>\r
                Cc: copy@company.local\r
                Subject: =?UTF-8?B?0J/RgNC40LLQtdGC?=\r
                Date: Mon, 12 Jan 2026 10:30:00 +0000\r
                Message-ID: <plain@x>\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                Первая строка\r
                Вторая строка\r
                """;

        MimeMailParser.ParsedMail parsed = parse(eml, false);

        assertEquals("<plain@x>", parsed.messageIdHeader());
        assertEquals("Анна <anna@example.test>", parsed.sender());
        assertEquals("Привет", parsed.subject());
        assertEquals(Instant.parse("2026-01-12T10:30:00Z"), parsed.internalDate());
        assertEquals(2, parsed.to().size());
        assertEquals("two@company.local", parsed.to().get(1).address());
        assertEquals("Two User", parsed.to().get(1).displayName());
        assertEquals("copy@company.local", parsed.cc().get(0).address());
        assertTrue(parsed.bodyText().contains("Первая строка"));
        assertNull(parsed.bodyHtml());
        assertTrue(parsed.attachments().isEmpty());
    }

    @Test
    void htmlOnlyMessageProducesReadableTextAndKeepsHtml() throws Exception {
        String eml = """
                From: sender@example.test\r
                Subject: HTML\r
                MIME-Version: 1.0\r
                Content-Type: text/html; charset=utf-8\r
                \r
                <html><head><style>.x{color:red}</style><script>alert(1)</script></head>
                <body><p>Hello&nbsp;&amp;&nbsp;welcome</p><p>Next &quot;line&quot; &#39;ok&#39;</p></body></html>\r
                """;

        MimeMailParser.ParsedMail parsed = parse(eml, false);

        assertEquals("Hello & welcome Next \"line\" 'ok'", parsed.bodyText());
        assertTrue(parsed.bodyHtml().contains("<p>Hello"));
        assertEquals("sender@example.test", parsed.sender());
    }

    @Test
    void parsesNestedAlternativesAndAttachmentMetadataWithoutStoring() throws Exception {
        String eml = """
                From: sender@example.test\r
                To: rcpt@company.local\r
                Subject: nested\r
                MIME-Version: 1.0\r
                Content-Type: multipart/mixed; boundary="mix"\r
                \r
                --mix\r
                Content-Type: multipart/alternative; boundary="alt"\r
                \r
                --alt\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                Plain body\r
                --alt\r
                Content-Type: text/html; charset=utf-8\r
                \r
                <b>HTML body</b>\r
                --alt--\r
                --mix\r
                Content-Type: APPLICATION/PDF; name="report.pdf"\r
                Content-Disposition: attachment; filename="report.pdf"\r
                Content-Transfer-Encoding: base64\r
                \r
                JVBERi0=\r
                --mix--\r
                """;

        MimeMailParser.ParsedMail parsed = parse(eml, false);

        assertTrue(parsed.bodyText().contains("Plain body"));
        assertTrue(parsed.bodyHtml().contains("HTML body"));
        assertEquals(1, parsed.attachments().size());
        assertEquals("report.pdf", parsed.attachments().get(0).fileName());
        assertEquals("application/pdf", parsed.attachments().get(0).contentType());
        assertNull(parsed.attachments().get(0).sha256());
        assertTrue(parsed.attachments().get(0).sizeBytes() >= 0);
    }

    @Test
    void unnamedAttachmentGetsSafeNameAndIsStored() throws Exception {
        String eml = """
                From: sender@example.test\r
                Subject: unnamed\r
                MIME-Version: 1.0\r
                Content-Type: multipart/mixed; boundary="b"\r
                \r
                --b\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                body\r
                --b\r
                Content-Type: application/octet-stream\r
                Content-Disposition: attachment\r
                Content-Transfer-Encoding: base64\r
                \r
                YmluYXJ5\r
                --b--\r
                """;

        MimeMailParser.ParsedMail parsed = parse(eml, true);

        assertEquals(1, parsed.attachments().size());
        assertEquals("attachment", parsed.attachments().get(0).fileName());
        assertEquals(6, parsed.attachments().get(0).sizeBytes());
        assertNotNull(parsed.attachments().get(0).sha256());
    }

    @Test
    void recursesIntoNestedRfc822Message() throws Exception {
        String eml = """
                From: outer@example.test\r
                Subject: outer\r
                MIME-Version: 1.0\r
                Content-Type: multipart/mixed; boundary="outer-b"\r
                \r
                --outer-b\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                Outer text\r
                --outer-b\r
                Content-Type: message/rfc822\r
                \r
                From: inner@example.test\r
                Subject: inner\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                Inner text\r
                --outer-b--\r
                """;

        MimeMailParser.ParsedMail parsed = parse(eml, false);

        assertTrue(parsed.bodyText().contains("Outer text"));
        assertTrue(parsed.bodyText().contains("Inner text"));
        assertTrue(parsed.attachments().isEmpty());
    }

    @Test
    void missingHeadersAndBrokenCharsetDoNotKillWholeMessage() throws Exception {
        String eml = """
                Content-Type: text/plain; charset=unknown-charset\r
                \r
                body that may be unreadable\r
                """;

        MimeMailParser.ParsedMail parsed = parse(eml, false);

        assertEquals("", parsed.subject());
        assertNull(parsed.sender());
        assertNotNull(parsed.internalDate());
        assertFalse(parsed.internalDate().isAfter(Instant.now().plusSeconds(5)));
    }

    @Test
    void snippetIsCollapsedAndTruncated() throws Exception {
        String repeated = "word ".repeat(100);
        String eml = "From: a@x\r\nSubject: long\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
                + repeated.replace(" ", "\r\n");

        MimeMailParser.ParsedMail parsed = parse(eml, false);

        assertEquals(200, parsed.snippet().length());
        assertFalse(parsed.snippet().contains("\n"));
        assertEquals("", MimeMailParser.truncate(null, 4));
        assertEquals("abc", MimeMailParser.truncate("abcdef", 3));
        assertEquals("abc", MimeMailParser.truncate("abc", 10));
    }

    private MimeMailParser.ParsedMail parse(String eml, boolean storeAttachments) throws Exception {
        return parser.parse(new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)), storeAttachments);
    }
}
