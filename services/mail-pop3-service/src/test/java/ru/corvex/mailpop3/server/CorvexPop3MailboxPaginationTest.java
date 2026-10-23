package ru.corvex.mailpop3.server;

import org.junit.jupiter.api.Test;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorvexPop3MailboxPaginationTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID inboxId = UUID.randomUUID();

    @Test
    void snapshotLoadsFollowingPagesUsingLastUidAsCursor() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        List<ProtocolStorageClient.MessageMeta> firstPage = messages(1, 500);
        ProtocolStorageClient.MessageMeta last = meta(501);
        when(storage.listMessages(userId, inboxId, 0L, 500)).thenReturn(firstPage);
        when(storage.listMessages(userId, inboxId, 500L, 500)).thenReturn(List.of(last));
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 1000);

        var snapshot = mailbox.getMessages();

        assertEquals(501, snapshot.size());
        assertEquals(last.id().toString(), snapshot.get(500).getUid());
        verify(storage).listMessages(userId, inboxId, 500L, 500);
    }

    @Test
    void fullPageFollowedByEmptyPageEndsSnapshot() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        when(storage.listMessages(userId, inboxId, 0L, 500)).thenReturn(messages(1, 500));
        when(storage.listMessages(userId, inboxId, 500L, 500)).thenReturn(List.of());
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 1000);

        assertEquals(500, mailbox.getMessages().size());
    }

    @Test
    void getMessageDelegatesRawStreamLookupToStorage() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        UUID messageId = UUID.randomUUID();
        InputStream expected = new ByteArrayInputStream(new byte[]{1, 2});
        when(storage.openRawById(userId, messageId)).thenReturn(expected);
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 10);

        assertSame(expected, mailbox.getMessage(messageId.toString()));
    }

    @Test
    void removeBeforeSnapshotDeletesWithoutLoadingMessages() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        UUID messageId = UUID.randomUUID();
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 10);

        mailbox.remove(messageId.toString());

        verify(storage).deleteMessage(userId, messageId);
        verify(storage, never()).listMessages(any(), any(), anyLong(), anyInt());
    }

    @Test
    void malformedRemoveUidDoesNotCallStorage() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 10);

        assertThrows(IOException.class, () -> mailbox.remove("not-a-uuid"));
        verify(storage, never()).deleteMessage(any(), any());
    }

    @Test
    void identifierContainsUserAndInboxAndCloseIsSafe() {
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(
                mock(ProtocolStorageClient.class), userId, inboxId, 10);

        assertEquals(userId + "/" + inboxId, mailbox.getIdentifier());
        assertDoesNotThrow(() -> mailbox.close());
    }

    private List<ProtocolStorageClient.MessageMeta> messages(int firstUid, int lastUid) {
        return IntStream.rangeClosed(firstUid, lastUid)
                .mapToObj(this::meta)
                .toList();
    }

    private ProtocolStorageClient.MessageMeta meta(long uid) {
        return new ProtocolStorageClient.MessageMeta(
                UUID.randomUUID(), uid, uid, "<m" + uid + "@corp.local>",
                "ivanov@corp.local", "Тема " + uid, Instant.now(), 100 + uid,
                false, false, false, false, false);
    }
}
