package ru.corvex.mailstorage.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.security.InternalTokenGuard;
import ru.corvex.mailstorage.service.ProtocolMailService;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Внутренний API для mail-imap-service / mail-pop3-service (этап 3). Только app-сеть +
 * X-Corvex-Internal-Token; gateway сюда не маршрутизирует. userId в пути протокольный
 * сервис получает из auth-service verify-password — хранилище не доверяет ему слепо и
 * каждую папку/письмо сверяет по owner_id (чужой объект = 404, без утечки существования).
 */
@RestController
@RequestMapping("/internal/protocol/users/{userId}")
public class InternalProtocolController {

    private final ProtocolMailService service;
    private final InternalTokenGuard tokens;

    public InternalProtocolController(ProtocolMailService service, InternalTokenGuard tokens) {
        this.service = service;
        this.tokens = tokens;
    }

    @GetMapping("/folders")
    List<ProtocolDtos.FolderInfo> folders(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                          @PathVariable UUID userId) {
        tokens.verify(token);
        return service.listFolders(userId);
    }

    @PostMapping("/folders")
    ResponseEntity<ProtocolDtos.FolderInfo> createFolder(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable UUID userId, @RequestBody ProtocolDtos.CreateFolderRequest req) {
        tokens.verify(token);
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createFolder(userId, req));
    }

    @GetMapping("/folders/{folderId}/status")
    ProtocolDtos.FolderInfo status(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                   @PathVariable UUID userId, @PathVariable UUID folderId) {
        tokens.verify(token);
        return service.folderStatus(userId, folderId);
    }

    @PatchMapping("/folders/{folderId}")
    ProtocolDtos.FolderInfo renameFolder(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable UUID userId, @PathVariable UUID folderId,
            @RequestBody ProtocolDtos.RenameFolderRequest req) throws IOException {
        tokens.verify(token);
        return service.renameFolder(userId, folderId, req);
    }

    @DeleteMapping("/folders/{folderId}")
    ResponseEntity<Void> deleteFolder(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                       @PathVariable UUID userId, @PathVariable UUID folderId) {
        tokens.verify(token);
        service.deleteFolder(userId, folderId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/folders/{folderId}/messages")
    List<ProtocolDtos.MessageMeta> messages(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                            @PathVariable UUID userId, @PathVariable UUID folderId,
                                            @RequestParam(defaultValue = "0") long afterUid,
                                            @RequestParam(defaultValue = "1000") int limit) {
        tokens.verify(token);
        return service.listMessages(userId, folderId, afterUid, limit);
    }

    @GetMapping("/folders/{folderId}/messages/{uid}/raw")
    ResponseEntity<InputStreamResource> rawByUid(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable UUID userId, @PathVariable UUID folderId, @PathVariable long uid) throws IOException {
        tokens.verify(token);
        return rawResponse(service.openByUid(userId, folderId, uid));
    }

    @GetMapping("/messages/{messageId}/raw")
    ResponseEntity<InputStreamResource> rawById(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable UUID userId, @PathVariable UUID messageId) throws IOException {
        tokens.verify(token);
        return rawResponse(service.openById(userId, messageId));
    }

    @PatchMapping("/folders/{folderId}/messages/{uid}/flags")
    ProtocolDtos.MessageMeta flags(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                   @PathVariable UUID userId, @PathVariable UUID folderId, @PathVariable long uid,
                                   @RequestBody ProtocolDtos.FlagsUpdate req) {
        tokens.verify(token);
        return service.updateFlags(userId, folderId, uid, req);
    }

    @PostMapping("/folders/{folderId}/expunge")
    ProtocolDtos.ExpungeResponse expunge(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                         @PathVariable UUID userId, @PathVariable UUID folderId,
                                         @RequestBody(required = false) ProtocolDtos.ExpungeRequest req) {
        tokens.verify(token);
        return service.expunge(userId, folderId, req);
    }

    /** IMAP APPEND: тело — сырой .eml; дата и флаги — заголовками, чтобы не плодить multipart. */
    @PostMapping(value = "/folders/{folderId}/messages", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<ProtocolDtos.AppendResponse> append(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @RequestHeader(value = "X-Corvex-Internal-Date", required = false) String internalDate,
            @RequestHeader(value = "X-Corvex-Flags", required = false) String flags,
            @PathVariable UUID userId, @PathVariable UUID folderId,
            HttpServletRequest request) throws IOException {
        tokens.verify(token);
        Instant date = null;
        if (internalDate != null && !internalDate.isBlank()) {
            try {
                date = Instant.parse(internalDate.trim());
            } catch (DateTimeParseException e) {
                throw new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "X-Corvex-Internal-Date должен быть ISO-8601 instant");
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.append(userId, folderId, request.getInputStream(), date,
                        ProtocolMailService.parseFlags(flags)));
    }

    @PostMapping("/folders/{folderId}/copy")
    ProtocolDtos.CopyResponse copy(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                   @PathVariable UUID userId, @PathVariable UUID folderId,
                                   @RequestBody ProtocolDtos.CopyRequest req) throws IOException {
        tokens.verify(token);
        return service.copyMessages(userId, folderId, req);
    }

    @DeleteMapping("/messages/{messageId}")
    ResponseEntity<Void> deleteMessage(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                        @PathVariable UUID userId, @PathVariable UUID messageId) {
        tokens.verify(token);
        service.hardDeleteMessage(userId, messageId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/quota")
    ProtocolDtos.QuotaView quota(@RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
                                 @PathVariable UUID userId) {
        tokens.verify(token);
        return service.quota(userId);
    }

    private static ResponseEntity<InputStreamResource> rawResponse(ProtocolMailService.RawMessage raw) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(raw.meta().sizeBytes())
                .header("X-Corvex-Message-Id", String.valueOf(raw.meta().id()))
                .header("X-Corvex-Message-Uid", String.valueOf(raw.meta().uid()))
                .body(new InputStreamResource(raw.stream()));
    }
}
