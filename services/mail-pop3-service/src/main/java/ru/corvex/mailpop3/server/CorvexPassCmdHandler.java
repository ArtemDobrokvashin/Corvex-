package ru.corvex.mailpop3.server;

import org.apache.james.core.Username;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.protocols.pop3.POP3Session;
import org.apache.james.protocols.pop3.core.AbstractPassCmdHandler;
import org.apache.james.protocols.pop3.mailbox.Mailbox;
import ru.corvex.mailpop3.client.Pop3AuthClient;
import ru.corvex.mailpop3.client.ProtocolStorageClient;

import java.io.IOException;

/**
 * Единственная точка интеграции James POP3 с Corvex-identity: PASS проверяется в
 * auth-service (основной пароль или app-password), INBOX резолвится в mail-storage.
 * null = AUTH_FAILED от James; IOException наверх = временная ошибка (-ERR Unexpected),
 * чтобы клиент не считал пароль неверным при недоступности бэкенда.
 */
public class CorvexPassCmdHandler extends AbstractPassCmdHandler {

    private final Pop3AuthClient auth;
    private final ProtocolStorageClient storage;
    private final int maxMessagesPerSession;

    public CorvexPassCmdHandler(MetricFactory metricFactory, Pop3AuthClient auth,
                                ProtocolStorageClient storage, int maxMessagesPerSession) {
        super(metricFactory);
        this.auth = auth;
        this.storage = storage;
        this.maxMessagesPerSession = maxMessagesPerSession;
    }

    @Override
    protected Mailbox auth(POP3Session session, Username username, String password) throws IOException {
        if (username == null) {
            return null;
        }
        var user = auth.authenticate(username.asString(), password);
        if (user.isEmpty()) {
            return null;
        }
        var inbox = storage.findInbox(user.get().userId());
        if (inbox.isEmpty()) {
            // Хранилище создаёт системные папки при первом listFolders; пустой ответ = аномалия бэкенда.
            throw new IOException("INBOX не найден для пользователя " + user.get().userId());
        }
        return new CorvexPop3Mailbox(storage, user.get().userId(), inbox.get().id(), maxMessagesPerSession);
    }
}
