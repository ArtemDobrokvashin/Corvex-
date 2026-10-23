package ru.corvex.mailpop3.server;

import org.apache.james.core.Username;
import org.apache.james.protocols.pop3.mailbox.Mailbox;
import org.junit.jupiter.api.Test;
import ru.corvex.mailpop3.client.Pop3AuthClient;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CorvexPassCmdHandlerEdgeCasesTest {

    private final Pop3AuthClient auth = mock(Pop3AuthClient.class);
    private final ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
    private final CorvexPassCmdHandler handler =
            new CorvexPassCmdHandler(NoopMetricFactory.INSTANCE, auth, storage, 25);

    @Test
    void nullUsernameIsAuthenticationFailureWithoutBackendCalls() throws Exception {
        assertNull(handler.auth(null, null, "secret"));

        verifyNoInteractions(auth, storage);
    }

    @Test
    void authenticatedMailboxIdentifierUsesResolvedInbox() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID inboxId = UUID.randomUUID();
        when(auth.authenticate("ivanov@corp.local", "secret")).thenReturn(
                Optional.of(new Pop3AuthClient.AuthenticatedUser(userId, "ivanov@corp.local")));
        when(storage.findInbox(userId)).thenReturn(Optional.of(
                new ProtocolStorageClient.FolderInfo(inboxId, null, "INBOX", "INBOX", 1, 1, 1, 0, 0)));

        Mailbox mailbox = handler.auth(null, Username.of("ivanov@corp.local"), "secret");

        assertEquals(userId + "/" + inboxId, mailbox.getIdentifier());
    }

    @Test
    void missingInboxAfterAuthenticationIsBackendFailure() throws Exception {
        UUID userId = UUID.randomUUID();
        when(auth.authenticate("ivanov@corp.local", "secret")).thenReturn(
                Optional.of(new Pop3AuthClient.AuthenticatedUser(userId, "ivanov@corp.local")));
        when(storage.findInbox(userId)).thenReturn(Optional.empty());

        IOException error = assertThrows(IOException.class,
                () -> handler.auth(null, Username.of("ivanov@corp.local"), "secret"));

        assertTrue(error.getMessage().contains("INBOX не найден"));
        assertTrue(error.getMessage().contains(userId.toString()));
    }

    @Test
    void storageFailureDuringInboxLookupPropagates() throws Exception {
        UUID userId = UUID.randomUUID();
        when(auth.authenticate("ivanov@corp.local", "secret")).thenReturn(
                Optional.of(new Pop3AuthClient.AuthenticatedUser(userId, "ivanov@corp.local")));
        when(storage.findInbox(userId)).thenThrow(new IOException("storage down"));

        IOException error = assertThrows(IOException.class,
                () -> handler.auth(null, Username.of("ivanov@corp.local"), "secret"));

        assertEquals("storage down", error.getMessage());
    }
}
