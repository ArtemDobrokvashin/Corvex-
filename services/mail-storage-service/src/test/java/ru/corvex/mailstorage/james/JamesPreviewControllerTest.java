package ru.corvex.mailstorage.james;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JamesPreviewControllerTest {
    private final JamesOwnMailboxAccess access = mock(JamesOwnMailboxAccess.class);
    private final JamesPreviewController controller = new JamesPreviewController(access);
    private final Authentication auth = mock(Authentication.class);

    @Test void guardsMethodsWithReadOwnAuthority() throws Exception {
        for (var method : new java.lang.reflect.Method[]{
                JamesPreviewController.class.getDeclaredMethod("folders", Authentication.class),
                JamesPreviewController.class.getDeclaredMethod("messages", Authentication.class, UUID.class, int.class)}) {
            assertEquals("hasAuthority('MAIL_READ_OWN')", method.getAnnotation(PreAuthorize.class).value());
        }
    }

    @Test void returnsScopedResultsWithoutCacheOrNativeState() throws Exception {
        UUID folder = UUID.randomUUID();
        when(access.listOwnFolders(auth)).thenReturn(List.of(new MailDtos.FolderView(folder, "Inbox", "INBOX", 1, 1)));
        var folderResponse = controller.folders(auth);
        assertEquals("private, no-store", folderResponse.getHeaders().getCacheControl());
        assertEquals("nosniff", folderResponse.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(folder, folderResponse.getBody().get(0).id());
        var preview = new JamesMessagePreviewMapper.Preview(UUID.randomUUID(), folder, "u@x", "subject", "body", Instant.EPOCH, 20, false, false, false, true, "UNVERIFIED");
        when(access.messagePage(auth, folder, 50, 0, null))
                .thenReturn(new JamesOwnMailboxAccess.FolderMessagePage(List.of(preview), "internal-state", true));
        var body = controller.messages(auth, folder, 50).getBody();
        assertEquals("UNVERIFIED", body.messages().get(0).avStatus());
        assertTrue(body.continuationUnavailable());
        assertEquals(2, JamesPreviewController.PreviewPage.class.getRecordComponents().length);
    }

    @Test void deniesUnsafeAccessAndDoesNotExposeUpstreamError() throws Exception {
        when(access.listOwnFolders(auth)).thenThrow(new SecurityException("sensitive detail"));
        var denied = assertThrows(ResponseStatusException.class, () -> controller.folders(auth));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertFalse(denied.getReason().contains("sensitive detail"));
        when(access.messagePage(eq(auth), any(), anyInt(), eq(0), isNull())).thenThrow(new IOException("secret"));
        var failure = assertThrows(ResponseStatusException.class, () -> controller.messages(auth, UUID.randomUUID(), 10));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        assertFalse(failure.getReason().contains("secret"));
    }

    @Test void rejectsInvalidLimitBeforeAccess() throws Exception {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> controller.messages(auth, UUID.randomUUID(), 0)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class, () -> controller.messages(auth, UUID.randomUUID(), 101)).getStatusCode());
        verifyNoInteractions(access);
    }
}
