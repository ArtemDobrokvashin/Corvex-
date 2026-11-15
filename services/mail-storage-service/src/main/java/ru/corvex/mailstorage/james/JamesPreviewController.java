package ru.corvex.mailstorage.james;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Separate opt-in read path, never switches the existing webmail automatically. */
@RestController
@RequestMapping("/api/v1/mail/james")
@ConditionalOnProperty(name = "corvex.james.read.enabled", havingValue = "true")
class JamesPreviewController {
    private final JamesOwnMailboxAccess access;
    JamesPreviewController(JamesOwnMailboxAccess access) { this.access = access; }

    public record Preview(UUID id, UUID folderId, String sender, String subject, String snippet,
                          Instant date, long sizeBytes, boolean seen, boolean flagged, boolean answered,
                          boolean hasAttachments, String avStatus) { }
    public record PreviewPage(List<Preview> messages, boolean continuationUnavailable) { }

    @GetMapping("/folders")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<List<MailDtos.FolderView>> folders(Authentication auth) {
        try {
            return response(access.listOwnFolders(auth));
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Mail access denied");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | GeneralSecurityException | IllegalStateException e) {
            throw unavailable();
        }
    }

    @GetMapping("/folders/{folderId}/messages")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<PreviewPage> messages(Authentication auth, @PathVariable UUID folderId,
                                         @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid limit");
        try {
            var page = access.messagePage(auth, folderId, limit, 0, null);
            var previews = page.messages().stream().map(p -> new Preview(p.id(), p.folderId(), p.sender(), p.subject(),
                    p.snippet(), p.date(), p.sizeBytes(), p.seen(), p.flagged(), p.answered(), p.hasAttachments(), p.avStatus())).toList();
            return response(new PreviewPage(previews, page.continuationUnavailable()));
        } catch (SecurityException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Mail access denied");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | GeneralSecurityException | IllegalStateException e) {
            throw unavailable();
        }
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "James mail preview unavailable");
    }
}
