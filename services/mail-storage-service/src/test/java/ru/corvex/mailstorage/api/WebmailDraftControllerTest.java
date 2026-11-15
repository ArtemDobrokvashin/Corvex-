package ru.corvex.mailstorage.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.draft.DraftPayload;
import ru.corvex.mailstorage.draft.WebmailDraftStore;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebmailDraftControllerTest {

    private static Jwt token(String subject) {
        return Jwt.withTokenValue("token").header("alg", "none").subject(subject)
                .claim("permissions", List.of("MAIL_READ_OWN")).build();
    }

    private static DraftPayload payload(String subject, String base64) {
        return new DraftPayload("user@company.local", "", subject, "body", "",
                null, List.of(new DraftPayload.Attachment("report.pdf", "application/pdf", base64)));
    }

    @Test void ownerComesFromTheTokenAndNeverFromThePath() {
        WebmailDraftStore store = mock(WebmailDraftStore.class);
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        WebmailDraftStore.Draft stored = new WebmailDraftStore.Draft(id, 3, payload("s", "YWJj"), Instant.now());
        when(store.get(owner, id)).thenReturn(stored);
        var controller = new WebmailDraftController(store);
        var view = controller.get(token(owner.toString()), id);
        assertEquals(3, view.revision());
        assertEquals(id, view.id());
        verify(store).get(owner, id);
        verify(store, never()).get(any(), eq(UUID.randomUUID()));
    }

    @Test void invalidSubjectIsRejectedWithoutTouchingStorage() {
        WebmailDraftStore store = mock(WebmailDraftStore.class);
        var controller = new WebmailDraftController(store);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.get(token("not-a-uuid"), UUID.randomUUID())).getStatusCode());
        verify(store, never()).get(any(), any());
    }

    @Test void createReturnsCreatedAndBumpsNothing() {
        WebmailDraftStore store = mock(WebmailDraftStore.class);
        UUID owner = UUID.randomUUID();
        DraftPayload body = payload("hello", "YWJj");
        when(store.create(eq(owner), any())).thenReturn(new WebmailDraftStore.Draft(UUID.randomUUID(), 1, body, Instant.now()));
        var controller = new WebmailDraftController(store);
        ResponseEntity<WebmailDraftController.DraftView> response = controller.create(token(owner.toString()), body);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(1, response.getBody().revision());
    }

    @Test void updateRequiresARevisionAndPassesItThrough() {
        WebmailDraftStore store = mock(WebmailDraftStore.class);
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        DraftPayload body = payload("edited", "YWJj");
        when(store.update(owner, id, 7, body)).thenReturn(new WebmailDraftStore.Draft(id, 8, body, Instant.now()));
        var controller = new WebmailDraftController(store);
        var view = controller.update(token(owner.toString()), id, new WebmailDraftController.UpdateRequest(7, body));
        assertEquals(8, view.revision());
        verify(store).update(owner, id, 7, body);
    }

    @Test void nullUpdateBodyIsBadRequest() {
        WebmailDraftStore store = mock(WebmailDraftStore.class);
        var controller = new WebmailDraftController(store);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.update(token(UUID.randomUUID().toString()), UUID.randomUUID(), null)).getStatusCode());
    }

    @Test void deleteUsesRevisionAndReturnsNoContent() {
        WebmailDraftStore store = mock(WebmailDraftStore.class);
        UUID owner = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var controller = new WebmailDraftController(store);
        ResponseEntity<Void> response = controller.delete(token(owner.toString()), id, 4);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(store).delete(owner, id, 4);
    }

    @Test void summaryCountsAttachmentsAndSizesWithoutTrustingTheClient() {
        WebmailDraftStore store = mock(WebmailDraftStore.class);
        UUID owner = UUID.randomUUID();
        // "YWJj" is 3 decoded bytes; "YWJjZA==" is 4.
        DraftPayload body = new DraftPayload("to@x", "", "subject", "body", "", null,
                List.of(new DraftPayload.Attachment("a.bin", "application/octet-stream", "YWJj"),
                        new DraftPayload.Attachment("b.bin", "application/octet-stream", "YWJjZA==")));
        when(store.list(owner, 50)).thenReturn(List.of(new WebmailDraftStore.Draft(UUID.randomUUID(), 2, body, Instant.now())));
        var controller = new WebmailDraftController(store);
        var list = controller.list(token(owner.toString()), 50);
        assertEquals(1, list.size());
        assertEquals(2, list.get(0).attachmentCount());
        assertEquals(7, list.get(0).sizeBytes());
        assertEquals("subject", list.get(0).subject());
    }

    @Test void everyEndpointRequiresTheOwnMailAuthority() throws Exception {
        for (Method method : WebmailDraftController.class.getDeclaredMethods()) {
            if (method.getAnnotation(PreAuthorize.class) == null) continue;
            assertEquals("hasAuthority('MAIL_READ_OWN')", method.getAnnotation(PreAuthorize.class).value(),
                    method.getName());
        }
        long guarded = java.util.Arrays.stream(WebmailDraftController.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(PreAuthorize.class) != null).count();
        assertTrue(guarded >= 5, "expected list/create/get/update/delete to be guarded");
    }
}
