package ru.corvex.officepreview.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.officepreview.security.InternalTokenGuard;
import ru.corvex.officepreview.service.OfficeConversionService;
import ru.corvex.officepreview.service.OfficeFileNames;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Внутренний endpoint: тело запроса — исходный Office-файл, ответ — PDF.
 * Права и антивирус проверяет вызывающий сервис; сюда приходят уже проверенные байты.
 */
@RestController
@RequestMapping("/internal/office-preview")
public class OfficePreviewController {

    private final InternalTokenGuard tokens;
    private final OfficeConversionService conversion;

    public OfficePreviewController(InternalTokenGuard tokens, OfficeConversionService conversion) {
        this.tokens = tokens;
        this.conversion = conversion;
    }

    @PostMapping(path = "/convert", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<InputStreamResource> convert(
            @RequestHeader(name = InternalTokenGuard.HEADER, required = false) String token,
            @RequestHeader(name = "X-Office-File-Name", required = false) String encodedName,
            @RequestHeader(name = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            HttpServletRequest request) throws IOException {

        tokens.verify(token);
        String fileName = OfficeFileNames.decode(encodedName);
        long declaredSize = contentLength == null ? -1 : contentLength;
        var result = conversion.convert(fileName, declaredSize, request.getInputStream());
        Path pdf = result.pdf();
        InputStream stream = Files.newInputStream(pdf);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(Files.size(pdf))
                .header("X-Office-Cache", result.cacheHit() ? "hit" : "miss")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(stream));
    }
}
