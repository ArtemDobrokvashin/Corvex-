package ru.corvex.chatfile.drive;

import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.corvex.chatfile.security.ChatFilePrincipal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Личный диск.
 *
 * <p>Gateway проксирует {@code /api/v1/drive/**} сюда. Скачивание идёт авторизованным
 * fetch/blob'ом; единственный путь без JWT — выданная владельцем ссылка
 * {@link #shared(String)}. Она проверяет отзыв и срок на каждый запрос.</p>
 */
@RestController
@RequestMapping("/api/v1/drive")
public class DriveController {

    private final DriveService drive;

    public DriveController(DriveService drive) {
        this.drive = drive;
    }

    @GetMapping
    List<DriveDtos.NodeView> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(defaultValue = "files") String view,
            @RequestParam(required = false) String q) {
        DriveService.View parsed = "trash".equalsIgnoreCase(view)
                ? DriveService.View.TRASH
                : DriveService.View.FILES;
        return drive.list(userId(jwt), parentId, parsed, q).stream()
                .map(DriveDtos.NodeView::from)
                .toList();
    }

    @GetMapping("/usage")
    DriveDtos.UsageView usage(@AuthenticationPrincipal Jwt jwt) {
        return DriveDtos.UsageView.from(drive.usage(userId(jwt)));
    }

    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    DriveDtos.NodeView createFolder(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody DriveDtos.FolderRequest request) {
        return DriveDtos.NodeView.from(
                drive.createFolder(userId(jwt), request.parentId(), request.name()));
    }

    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    DriveDtos.NodeView upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID parentId,
            @RequestPart("file") MultipartFile file) {
        return DriveDtos.NodeView.from(drive.upload(userId(jwt), parentId, file));
    }

    @PatchMapping("/{id}/name")
    DriveDtos.NodeView rename(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody DriveDtos.RenameRequest request) {
        return DriveDtos.NodeView.from(drive.rename(userId(jwt), id, request.name()));
    }

    @PostMapping("/{id}/move")
    DriveDtos.NodeView move(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody DriveDtos.MoveRequest request) {
        return DriveDtos.NodeView.from(drive.move(userId(jwt), id, request.parentId()));
    }

    @PostMapping("/{id}/trash")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void trash(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        drive.trash(userId(jwt), id);
    }

    @PostMapping("/{id}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        drive.restore(userId(jwt), id);
    }

    /** Окончательное удаление доступно только для объекта, который уже в корзине. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void purge(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        drive.purge(userId(jwt), id);
    }

    @GetMapping("/{id}/content")
    ResponseEntity<Resource> content(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        DriveService.Download download = drive.download(userId(jwt), id);
        return attachment(download, false);
    }

    @GetMapping("/{id}/shares")
    List<DriveDtos.ShareView> shares(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return drive.shares(userId(jwt), id).stream()
                .map(DriveDtos.ShareView::from)
                .toList();
    }

    @PostMapping("/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    DriveDtos.ShareCreatedView createShare(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,
            @RequestBody(required = false) DriveDtos.ShareRequest request) {
        DriveService.ShareCreated created = drive.share(
                userId(jwt), id, request == null ? null : request.expiresInDays());
        String path = "/api/v1/drive/shared/" + created.token();
        return new DriveDtos.ShareCreatedView(
                DriveDtos.ShareView.from(created.share()), created.token(), path);
    }

    @DeleteMapping("/shares/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeShare(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID shareId) {
        drive.revokeShare(userId(jwt), shareId);
    }

    /**
     * Публичная ссылка. Никакой информации о владельце и структуре каталога не отдаётся:
     * только сам файл, принудительно как attachment.
     */
    @GetMapping("/shared/{token}")
    ResponseEntity<Resource> shared(@PathVariable String token) {
        DriveService.Download download = drive.downloadByToken(token);
        return attachment(download, true);
    }

    private ResponseEntity<Resource> attachment(DriveService.Download download, boolean publicLink) {
        DriveNode node = download.node();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(parseMediaType(node.contentType()));
        headers.setContentLength(node.sizeBytes());
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(node.name(), StandardCharsets.UTF_8)
                .build());
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        if (publicLink) {
            // Токен не должен утекать в чужие логи через Referer, а ответ не кэшируется:
            // отзыв ссылки обязан действовать со следующего запроса.
            headers.set("Referrer-Policy", "no-referrer");
        }
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

    private UUID userId(Jwt jwt) {
        return ChatFilePrincipal.from(jwt).userId();
    }
}
