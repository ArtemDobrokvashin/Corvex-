package ru.corvex.mailstorage.api;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.client.OfficePreviewClient;
import ru.corvex.mailstorage.service.MailApiService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Предпросмотр Office-вложения как PDF. Отдельный контроллер, чтобы не менять конструктор
 * MailController и его существующие тесты; права и AV те же, что у скачивания.
 */
@RestController
@RequestMapping("/api/v1/mail")
public class MailAttachmentPreviewController {

    private final MailApiService mail;
    private final OfficePreviewClient preview;

    public MailAttachmentPreviewController(MailApiService mail, OfficePreviewClient preview) {
        this.mail = mail;
        this.preview = preview;
    }

    @GetMapping("/messages/{id}/attachments/{partIndex}/preview")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<InputStreamResource> preview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                @PathVariable int partIndex) throws IOException {
        var file = mail.downloadAttachment(userId(jwt), id, partIndex);
        if (!preview.enabled()) {
            try (InputStream ignored = file.stream()) {
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Предпросмотр Office выключен");
            }
        }
        InputStream pdf;
        try (InputStream source = file.stream()) {
            pdf = preview.convert(source, file.sizeBytes(), file.fileName());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(pdfName(file.fileName()), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new InputStreamResource(pdf));
    }

    private static String pdfName(String source) {
        String name = source == null ? "document" : source.replaceAll("[\\\\/\\p{Cntrl}]", "_");
        if (name.isBlank()) {
            name = "document";
        }
        return name.toLowerCase().endsWith(".pdf") ? name : name + ".pdf";
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject не является UUID пользователя");
        }
    }
}
