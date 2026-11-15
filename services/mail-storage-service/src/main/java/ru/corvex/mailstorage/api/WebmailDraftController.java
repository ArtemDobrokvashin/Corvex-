package ru.corvex.mailstorage.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.draft.DraftPayload;
import ru.corvex.mailstorage.draft.WebmailDraftStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Owner-scoped webmail drafts. The owner always comes from the JWT subject;
 * a draft id alone never grants access. Updates require the last known
 * revision, so a second tab cannot silently overwrite newer text.
 */
@RestController
@RequestMapping("/api/v1/mail/drafts")
public class WebmailDraftController {

    private final WebmailDraftStore store;

    public WebmailDraftController(WebmailDraftStore store) {
        this.store = store;
    }

    public record DraftView(UUID id, long revision, DraftPayload payload, Instant updatedAt) { }
    public record DraftSummary(UUID id, long revision, String to, String subject, int attachmentCount,
                               long sizeBytes, Instant updatedAt) { }
    public record UpdateRequest(long revision, DraftPayload payload) { }

    @GetMapping
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    List<DraftSummary> list(@AuthenticationPrincipal Jwt jwt,
                           @RequestParam(defaultValue = "50") int limit) {
        return store.list(userId(jwt), limit).stream()
                .map(draft -> new DraftSummary(draft.id(), draft.revision(), draft.payload().to(),
                        draft.payload().subject(), attachmentCount(draft.payload()), sizeBytes(draft.payload()),
                        draft.updatedAt()))
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<DraftView> create(@AuthenticationPrincipal Jwt jwt, @RequestBody DraftPayload payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(view(store.create(userId(jwt), payload)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    DraftView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return view(store.get(userId(jwt), id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    DraftView update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                     @RequestBody UpdateRequest request) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Черновик не задан");
        return view(store.update(userId(jwt), id, request.revision(), request.payload()));
    }

    /** Returns the stored revision so a client can delete exactly what it last read. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('MAIL_READ_OWN')")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @RequestParam long revision) {
        store.delete(userId(jwt), id, revision);
        return ResponseEntity.noContent().build();
    }

    private static DraftView view(WebmailDraftStore.Draft draft) {
        return new DraftView(draft.id(), draft.revision(), draft.payload(), draft.updatedAt());
    }

    private static int attachmentCount(DraftPayload payload) {
        return payload.attachments() == null ? 0 : payload.attachments().size();
    }

    /** Approximate stored size; never trusts client-provided lengths. */
    private static long sizeBytes(DraftPayload payload) {
        long total = 0;
        if (payload.attachments() == null) return total;
        for (DraftPayload.Attachment file : payload.attachments()) {
            String base64 = file == null ? null : file.contentBase64();
            if (base64 == null) continue;
            int padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
            total += Math.max(0, base64.length() / 4 * 3 - padding);
        }
        return total;
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject не является UUID пользователя");
        }
    }
}
