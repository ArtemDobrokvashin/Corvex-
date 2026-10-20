package ru.corvex.mailpop3.server;

import org.apache.james.protocols.pop3.mailbox.Mailbox;
import org.apache.james.protocols.pop3.mailbox.MessageMetaData;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Адаптер James POP3 Mailbox -> mail-storage-service. Семантика RFC 1939:
 * <ul>
 *   <li>сессия работает со <b>снапшотом</b> INBOX на момент логина (новые письма видны
 *       только следующей сессии — так POP3 гарантирует стабильные номера сообщений);</li>
 *   <li>UIDL = UUID строки message_metadata: стабилен между сессиями и уникален глобально,
 *       клиентский «оставлять на сервере N дней» работает корректно;</li>
 *   <li>James сам держит DELE-пометки в сессии и зовёт remove() только на QUIT —
 *       обрыв соединения без QUIT ничего не удаляет, как требует RFC.</li>
 * </ul>
 */
public class CorvexPop3Mailbox implements Mailbox {

    private static final int PAGE_SIZE = 500;

    private final ProtocolStorageClient storage;
    private final UUID userId;
    private final UUID inboxFolderId;
    private final int maxMessages;
    private List<MessageMetaData> snapshot;

    public CorvexPop3Mailbox(ProtocolStorageClient storage, UUID userId, UUID inboxFolderId, int maxMessages) {
        this.storage = storage;
        this.userId = userId;
        this.inboxFolderId = inboxFolderId;
        this.maxMessages = Math.max(1, maxMessages);
    }

    @Override
    public synchronized List<MessageMetaData> getMessages() throws IOException {
        if (snapshot == null) {
            snapshot = loadSnapshot();
        }
        return snapshot;
    }

    private List<MessageMetaData> loadSnapshot() throws IOException {
        List<MessageMetaData> out = new ArrayList<>();
        long afterUid = 0;
        while (out.size() < maxMessages) {
            List<ProtocolStorageClient.MessageMeta> page =
                    storage.listMessages(userId, inboxFolderId, afterUid, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (ProtocolStorageClient.MessageMeta m : page) {
                out.add(new MessageMetaData(m.id().toString(), m.sizeBytes()));
                afterUid = m.uid();
                if (out.size() >= maxMessages) {
                    break;
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        return out;
    }

    @Override
    public InputStream getMessage(String uid) throws IOException {
        return storage.openRawById(userId, parseUid(uid));
    }

    @Override
    public synchronized void remove(String... uids) throws IOException {
        for (String uid : uids) {
            storage.deleteMessage(userId, parseUid(uid));
            if (snapshot != null) {
                snapshot.removeIf(m -> m.getUid().equals(uid));
            }
        }
    }

    @Override
    public String getIdentifier() {
        return userId + "/" + inboxFolderId;
    }

    @Override
    public void close() {
        // HTTP-клиент общий на сервис; per-session ресурсов не держим.
    }

    private static UUID parseUid(String uid) throws IOException {
        try {
            return UUID.fromString(uid);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Некорректный POP3 UID: " + uid);
        }
    }
}
