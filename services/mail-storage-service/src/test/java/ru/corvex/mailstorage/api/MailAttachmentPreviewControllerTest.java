package ru.corvex.mailstorage.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.client.OfficePreviewClient;
import ru.corvex.mailstorage.service.MailApiService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailAttachmentPreviewControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MESSAGE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private MailApiService mail;
    private OfficePreviewClient preview;
    private MailAttachmentPreviewController controller;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        mail = mock(MailApiService.class);
        preview = mock(OfficePreviewClient.class);
        controller = new MailAttachmentPreviewController(mail, preview);
        jwt = Jwt.withTokenValue("token").header("alg", "none").subject(USER_ID.toString())
                .claim("permissions", List.of("MAIL_READ_OWN")).build();
    }

    @Test
    void streamsPdfWithInlineHeaders() throws Exception {
        byte[] source = "docx".getBytes(StandardCharsets.UTF_8);
        when(mail.downloadAttachment(USER_ID, MESSAGE_ID, 0))
                .thenReturn(new MailApiService.AttachmentDownload(
                        new ByteArrayInputStream(source), "report.docx", source.length));
        when(preview.enabled()).thenReturn(true);
        when(preview.convert(any(InputStream.class), eq((long) source.length), eq("report.docx")))
                .thenReturn(new ByteArrayInputStream("%PDF-1.4".getBytes(StandardCharsets.UTF_8)));

        var response = controller.preview(jwt, MESSAGE_ID, 0);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("inline"));
        assertTrue(disposition.contains("report.docx.pdf"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("private, no-store", response.getHeaders().getCacheControl());
        assertEquals("%PDF-1.4", new String(response.getBody().getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
    }

    @Test
    void disabledPreviewReturnsNotImplemented() throws Exception {
        when(mail.downloadAttachment(USER_ID, MESSAGE_ID, 0))
                .thenReturn(new MailApiService.AttachmentDownload(
                        new ByteArrayInputStream(new byte[]{1}), "report.docx", 1));
        when(preview.enabled()).thenReturn(false);

        var error = assertThrows(ResponseStatusException.class, () -> controller.preview(jwt, MESSAGE_ID, 0));
        assertEquals(501, error.getStatusCode().value());
    }

    @Test
    void antivirusRejectionPropagatesBeforeConverter() throws Exception {
        // downloadAttachment объявлен с throws IOException: стаб mock-а проверяется
        // компилятором так же, как реальный вызов.
        when(mail.downloadAttachment(USER_ID, MESSAGE_ID, 0))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Вложение заблокировано антивирусом"));

        var error = assertThrows(ResponseStatusException.class, () -> controller.preview(jwt, MESSAGE_ID, 0));
        assertEquals(403, error.getStatusCode().value());
    }
}
