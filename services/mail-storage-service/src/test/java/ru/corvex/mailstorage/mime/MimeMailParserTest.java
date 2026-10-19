package ru.corvex.mailstorage.mime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.corvex.mailstorage.storage.MaildirStorage;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MimeMailParserTest {

    @TempDir
    Path tmp;

    @Test
    void extractsTextSnippetAndStoresAttachmentInCas() throws Exception {
        MaildirStorage maildir = new MaildirStorage(tmp.resolve("mail"), tmp.resolve("attachments"));
        MimeMailParser parser = new MimeMailParser(maildir);
        String eml = """
                From: Анна <anna@partner.example>\r
                To: Ivanov@company.local\r
                Subject: Contract\r
                Message-ID: <m1@partner.example>\r
                MIME-Version: 1.0\r
                Content-Type: multipart/mixed; boundary="b1"\r
                \r
                --b1\r
                Content-Type: text/plain; charset=utf-8\r
                \r
                Hello, see attachment.\r
                --b1\r
                Content-Type: text/plain; name="hello.txt"\r
                Content-Disposition: attachment; filename="hello.txt"\r
                Content-Transfer-Encoding: base64\r
                \r
                aGVsbG8=\r
                --b1--\r
                """;

        MimeMailParser.ParsedMail parsed = parser.parse(
                new ByteArrayInputStream(eml.getBytes(StandardCharsets.UTF_8)), true);

        assertEquals("Contract", parsed.subject());
        assertTrue(parsed.bodyText().contains("Hello, see attachment."));
        assertTrue(parsed.snippet().contains("Hello"));
        assertEquals(1, parsed.attachments().size());
        assertEquals("hello.txt", parsed.attachments().get(0).fileName());
        assertEquals(5, parsed.attachments().get(0).sizeBytes());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                parsed.attachments().get(0).sha256());
    }
}
