package ru.corvex.officepreview.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfficeFileNamesTest {

    @Test
    void decodesBase64UrlAndKeepsOnlyBaseName() {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("../../etc/Отчёт 2026.docx".getBytes(StandardCharsets.UTF_8));

        assertEquals("Отчёт 2026.docx", OfficeFileNames.decode(encoded));
    }

    @Test
    void stripsControlCharactersAndBackslashes() {
        assertEquals("report.docx", OfficeFileNames.sanitize("C:\\temp\\report.docx"));
        assertEquals("a_b.docx", OfficeFileNames.sanitize("a\u0000b.docx"));
    }

    @Test
    void rejectsMissingOrInvalidHeader() {
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> OfficeFileNames.decode(null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> OfficeFileNames.decode("not base64!")).getStatusCode().value());
    }

    @Test
    void allowsOnlyOfficeExtensions() {
        assertEquals("docx", OfficeFileNames.extension("report.DOCX"));
        assertEquals("rtf", OfficeFileNames.extension("letter.rtf"));
        assertEquals(415, assertThrows(ResponseStatusException.class,
                () -> OfficeFileNames.extension("malware.exe")).getStatusCode().value());
        assertEquals(415, assertThrows(ResponseStatusException.class,
                () -> OfficeFileNames.extension("README")).getStatusCode().value());
    }

    @Test
    void buildsPdfFileName() {
        assertEquals("report.docx.pdf", OfficeFileNames.pdfName("report.docx"));
        assertEquals("already.pdf", OfficeFileNames.pdfName("already.pdf"));
    }
}
