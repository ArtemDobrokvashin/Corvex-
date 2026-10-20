package ru.corvex.mailpop3.server;

import org.apache.james.protocols.pop3.mailbox.MessageMetaData;
import org.junit.jupiter.api.Test;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Семантика POP3-снапшота: стабильные UIDL (UUID письма), постраничная загрузка, remove на QUIT. */
class CorvexPop3MailboxTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID inboxId = UUID.randomUUID();

    private ProtocolStorageClient.MessageMeta meta(long uid, long size) {
        return new ProtocolStorageClient.MessageMeta(UUID.randomUUID(), uid, "<m" + uid + "@x>",
                "ivanov@corp.local", "Тема " + uid, Instant.now(), size,
                false, false, false, false, false);
    }

    @Test
    void snapshotLoadsOnceAndExposesStableUuidUids() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        var m1 = meta(1, 100);
        var m2 = meta(2, 250);
        when(storage.listMessages(userId, inboxId, 0L, 500)).thenReturn(List.of(m1, m2));
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 1000);

        List<MessageMetaData> first = mailbox.getMessages();
        List<MessageMetaData> second = mailbox.getMessages();

        assertEquals(2, first.size());
        assertEquals(m1.id().toString(), first.get(0).getUid(), "UIDL должен быть UUID письма — стабилен между сессиями");
        assertEquals(250, first.get(1).getSize());
        assertEquals(first, second);
        verify(storage, times(1)).listMessages(userId, inboxId, 0L, 500);
    }

    @Test
    void snapshotRespectsMaxMessagesCap() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        when(storage.listMessages(userId, inboxId, 0L, 500)).thenReturn(List.of(meta(1, 1), meta(2, 1), meta(3, 1)));
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 2);

        assertEquals(2, mailbox.getMessages().size());
    }

    @Test
    void removeDeletesFromStorageAndPrunesSnapshot() throws Exception {
        ProtocolStorageClient storage = mock(ProtocolStorageClient.class);
        var m1 = meta(1, 100);
        when(storage.listMessages(userId, inboxId, 0L, 500)).thenReturn(List.of(m1));
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(storage, userId, inboxId, 1000);
        mailbox.getMessages();

        mailbox.remove(m1.id().toString());

        verify(storage).deleteMessage(userId, m1.id());
        assertEquals(0, mailbox.getMessages().size());
    }

    @Test
    void malformedUidIsIOExceptionNotCrash() {
        CorvexPop3Mailbox mailbox = new CorvexPop3Mailbox(mock(ProtocolStorageClient.class), userId, inboxId, 10);
        assertThrows(IOException.class, () -> mailbox.getMessage("not-a-uuid"));
    }
}
