package ru.corvex.chatfile.api;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.corvex.chatfile.security.ChatFilePrincipal;
import ru.corvex.chatfile.service.ChatFileService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Gateway маршрутизирует /api/v1/files/** сюда без срезания префикса.
 * Скачивание намеренно идёт через авторизованный fetch/blob на frontend, а не публичной ссылкой.
 */
@RestController
@RequestMapping("/api/v1/files")
public class ChatFileController {

    private final ChatFileService files;

    public ChatFileController(ChatFileService files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CHAT_SEND_FILES')")
    ChatFileDtos.ChatFileUploadView upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam UUID roomId,
            @RequestPart("file") MultipartFile file) {
        return ChatFileDtos.ChatFileUploadView.from(
                files.upload(ChatFilePrincipal.from(jwt), roomId, file));
    }

    @GetMapping("/{fileRef}")
    @PreAuthorize("hasAuthority('CHAT_READ')")
    ResponseEntity<Resource> download(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String fileRef) {
        ChatFileService.Download download = files.download(ChatFilePrincipal.from(jwt), fileRef);
        var metadata = download.metadata();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(parseMediaType(metadata.contentType()));
        headers.setContentLength(metadata.sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(metadata.fileName(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.setETag("\"" + metadata.sha256() + "\"");
        headers.set("X-Content-Type-Options", "nosniff");

        return new ResponseEntity<>(download.resource(), headers, HttpStatus.OK);
    }

    private MediaType parseMediaType(String contentType) {
        try {
            return contentType == null || contentType.isBlank()
                    ? MediaType.APPLICATION_OCTET_STREAM
                    : MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
