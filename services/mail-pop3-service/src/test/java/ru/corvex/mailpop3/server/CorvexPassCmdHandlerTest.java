package ru.corvex.mailpop3.server;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;
import ru.corvex.mailpop3.client.Pop3AuthClient;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** PASS-адаптер: null на неверный пароль (James -> -ERR auth failed), IOException на сбой бэкенда. */
class CorvexPassCmdHandlerTest {

    private final Pop3AuthClient auth = mock(Pop3AuthClient.class);
    private final ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
    private final CorvexPassCmdHandler handler =
            new CorvexPassCmdHandler(NoopMetricFactory.INSTANCE, auth, storage, 1000);

    @Test
    void validCredentialsYieldMailbox() throws Exception {
        UUID userId = UUID.randomUUID();
        when(auth.authenticate("ivanov@corp.local", "secret"))
                .thenReturn(Optional.of(new Pop3AuthClient.AuthenticatedUser(userId, "ivanov@corp.local")));
        when(storage.findInbox(userId)).thenReturn(Optional.of(
                new ProtocolStorageClient.FolderInfo(UUID.randomUUID(), null, "INBOX", "INBOX", 1, 1, 1, 0, 0)));

        assertNotNull(handler.auth(null, Username.of("ivanov@corp.local"), "secret"));
    }

    @Test
    void badCredentialsYieldNull() throws Exception {
        when(auth.authenticate("ivanov@corp.local", "wrong")).thenReturn(Optional.empty());

        assertNull(handler.auth(null, Username.of("ivanov@corp.local"), "wrong"));
    }

    @Test
    void backendFailurePropagatesAsIOException() throws Exception {
        when(auth.authenticate("ivanov@corp.local", "secret")).thenThrow(new IOException("auth down"));

        assertThrows(IOException.class, () -> handler.auth(null, Username.of("ivanov@corp.local"), "secret"));
    }
}
