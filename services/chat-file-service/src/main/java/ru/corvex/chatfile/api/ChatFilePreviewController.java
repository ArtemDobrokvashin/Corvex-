package ru.corvex.chatfile.api;

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
import ru.corvex.chatfile.client.OfficePreviewClient;
import ru.corvex.chatfile.security.ChatFilePrincipal;
import ru.corvex.chatfile.service.ChatFileService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Предпросмотр Office-файла чата как PDF. Членство и AV-статус проверяет тот же
 * ChatFileService.download, что и обычное скачивание (423 для непроверенного файла).
 */
@RestController
@RequestMapping("/api/v1/files")
public class ChatFilePreviewController {

    private final ChatFileService files;
    private final OfficePreviewClient preview;

    public ChatFilePreviewController(ChatFileService files, OfficePreviewClient preview) {
        this.files = files;
        this.preview = preview;
    }

    @GetMapping("/{fileRef}/preview")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ResponseEntity<InputStreamResource> preview(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable String fileRef) throws IOException {
        ChatFileService.Download download = files.download(ChatFilePrincipal.from(jwt), fileRef);
        if (!preview.enabled()) {
            try (InputStream ignored = download.resource().getInputStream()) {
                throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Предпросмотр Office выключен");
            }
        }
        InputStream pdf;
        try (InputStream source = download.resource().getInputStream()) {
            pdf = preview.convert(source, download.metadata().sizeBytes(), download.metadata().fileName());
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(pdfName(download.metadata().fileName()), StandardCharsets.UTF_8).build().toString())
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
}
