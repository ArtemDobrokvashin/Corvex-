package ru.corvex.mailstorage.external;

import ru.corvex.mailstorage.domain.ExternalMailAccount;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Транспорт внешнего ящика. Реализация на Jakarta Mail; интерфейс нужен для тестов синхронизации. */
public interface ExternalMailClient {

    record AccountConfig(
            UUID accountId,
            UUID ownerId,
            String emailAddress,
            String displayName,
            ExternalMailAccount.Provider provider,
            ExternalMailAccount.Protocol incomingProtocol,
            String incomingHost,
            int incomingPort,
            ExternalMailAccount.Security incomingSecurity,
            String incomingUsername,
            String secret,
            boolean smtpEnabled,
            String smtpHost,
            Integer smtpPort,
            ExternalMailAccount.Security smtpSecurity,
            String smtpUsername,
            boolean markSeenOnServer,
            boolean deleteAfterFetch) {
    }

    record TestResult(boolean ok, String message, int folderCount, int inboxMessageCount) {
    }

    record RemoteFolderInfo(
            String name,
            String delimiter,
            Long UIDValidity,
            long uidNext,
            int messageCount,
            Set<String> attributes,
            String specialUse) {
    }

    record RemoteMessageEnvelope(
            String remoteFolder,
            String remoteUid,
            Long UIDValidity,
            String messageIdHeader,
            String from,
            Instant internalDate,
            long sizeBytes,
            boolean seen,
            boolean answered,
            boolean flagged,
            boolean draft,
            boolean deleted) {
    }

    @FunctionalInterface
    interface RawMessageSource {
        InputStream open() throws IOException;
    }

    enum HandleResult {QUEUED, SKIPPED, DELETE_ON_SERVER}

    @FunctionalInterface
    interface MessageHandler {
        HandleResult handle(RemoteMessageEnvelope envelope, RawMessageSource source) throws Exception;
    }

    /** Обработчик просит остановить папку после ошибки; курсор останется на последнем успешном письме. */
    class StopFolderException extends RuntimeException {
        public StopFolderException(Throwable cause) {
            super(cause);
        }
    }

    record FetchStats(int seenByClient, int handled, boolean stopped) {
    }

    class ExternalMailClientException extends Exception {
        public enum Category {AUTH, TLS, NETWORK, PROTOCOL, CONFIG}

        private final Category category;
        private final boolean temporary;

        public ExternalMailClientException(Category category, boolean temporary, String message, Throwable cause) {
            super(message, cause);
            this.category = category;
            this.temporary = temporary;
        }

        public Category category() { return category; }

        public boolean temporary() { return temporary; }
    }

    TestResult testIncoming(AccountConfig config) throws ExternalMailClientException;

    TestResult testSmtp(AccountConfig config) throws ExternalMailClientException;

    List<RemoteFolderInfo> listFolders(AccountConfig config) throws ExternalMailClientException;

    List<RemoteMessageEnvelope> listMessageEnvelopes(AccountConfig config, String remoteFolder, int limit)
            throws ExternalMailClientException;

    FetchStats fetchFolder(AccountConfig config, String remoteFolder, long afterUid, int limit,
                           MessageHandler handler) throws ExternalMailClientException;
}
