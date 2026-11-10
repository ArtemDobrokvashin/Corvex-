package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.core.io.InputStreamResource;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.service.MailApiService;
import ru.corvex.mailstorage.service.MailSendService;
import ru.corvex.mailstorage.service.ProtocolMailService;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Webmail read API этапа 2. Gateway маршрутизирует /api/v1/mail/** сюда без срезания префикса,
 * поэтому mapping включает полный публичный путь. Владелец всегда берётся из JWT subject.
 */
@RestController
@RequestMapping("/api/v1/mail")
public class MailController {

    private final MailApiService mail;
    private final MailSendService sender;
    private final ProtocolMailService protocol;

    public MailController(MailApiService mail, MailSendService sender, ProtocolMailService protocol) {
        this.mail = mail;
        this.sender = sender;
        this.protocol = protocol;
    }

    @GetMapping("/quota")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    MailDtos.QuotaView quota(@AuthenticationPrincipal Jwt jwt) {
        var quota = protocol.quota(userId(jwt));
        return new MailDtos.QuotaView(quota.usedBytes(), quota.quotaBytes(), quota.messageCount());
    }

    @GetMapping("/folders")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    List<MailDtos.FolderView> folders(@AuthenticationPrincipal Jwt jwt) {
        return mail.listFolders(userId(jwt));
    }

    @GetMapping("/folders/{folderId}/messages")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    List<MailDtos.MessageListItem> messages(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable UUID folderId,
                                            @RequestParam(required = false) Long cursorUid,
                                            @RequestParam(defaultValue = "50") int limit) {
        return mail.listMessages(userId(jwt), folderId, cursorUid, limit);
    }

    @GetMapping("/messages/{id}")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    MailDtos.MessageDetail message(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) throws IOException {
        return mail.getMessage(userId(jwt), id);
    }

    @GetMapping("/messages/{id}/attachments/{partIndex}")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<InputStreamResource> attachment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @PathVariable int partIndex) throws IOException {
        var file = mail.downloadAttachment(userId(jwt), id, partIndex);
        String name = file.fileName().replaceAll("[\\\\/\\p{Cntrl}]", "_");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new InputStreamResource(file.stream()));
    }

    @PatchMapping("/messages/{id}/flags")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    MailDtos.MessageListItem flags(@AuthenticationPrincipal Jwt jwt,
                                   @PathVariable UUID id,
                                   @RequestBody MailDtos.UpdateFlagsRequest req) {
        return mail.updateFlags(userId(jwt), id, req);
    }

    @PostMapping("/messages/{id}/move")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    MailDtos.MessageListItem move(@AuthenticationPrincipal Jwt jwt,
                                  @PathVariable UUID id,
                                  @RequestBody MailDtos.MoveMessageRequest req) throws IOException {
        return mail.moveMessage(userId(jwt), id, req.targetFolderId());
    }

    @PostMapping("/messages/send")
    @PreAuthorize("hasAnyAuthority('MAIL_SEND_INTERNAL','MAIL_SEND_EXTERNAL')")
    MailDtos.SendMessageResult send(@AuthenticationPrincipal Jwt jwt,
                                    @RequestBody MailDtos.SendMessageRequest req) throws IOException {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        Set<String> permissionSet = permissions == null ? Set.of() : new HashSet<>(permissions);
        return sender.send(userId(jwt), permissionSet, req);
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject не является UUID пользователя");
        }
    }
}
