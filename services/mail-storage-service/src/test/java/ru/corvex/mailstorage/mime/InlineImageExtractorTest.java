package ru.corvex.mailstorage.mime;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class InlineImageExtractorTest {
    private final InlineImageExtractor extractor = new InlineImageExtractor();
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/aS0AAAAASUVORK5CYII=";
    private String part(String id, String type, String data) {
        return "--boundary\r\nContent-Type: " + type + "\r\nContent-ID: <" + id + ">\r\nContent-Transfer-Encoding: base64\r\n\r\n" + data + "\r\n";
    }
    private InlineImageExtractor.Image read(String body, String id) throws Exception {
        String mail = "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=boundary\r\n\r\n" + body + "--boundary--\r\n";
        return extractor.extract(new ByteArrayInputStream(mail.getBytes(StandardCharsets.US_ASCII)), id);
    }
    @Test void extractsRasterWithoutFilename() throws Exception {
        var image = read(part("logo@example", "image/png", PNG), "logo@example");
        assertNotNull(image); assertEquals("image/png", image.contentType()); assertTrue(image.bytes().length > 20);
    }
    @Test void rejectsDuplicatesWrongTypesAndMismatchedBytes() throws Exception {
        assertNull(read(part("logo", "image/png", PNG) + part("logo", "image/png", PNG), "logo"));
        assertNull(read(part("logo", "image/svg+xml", PNG), "logo"));
        assertNull(read(part("logo", "image/jpeg", PNG), "logo"));
        assertNull(read(part("logo", "image/png", PNG), "missing"));
        assertNull(read(part("logo", "image/png", PNG), "../\n"));
    }
}
