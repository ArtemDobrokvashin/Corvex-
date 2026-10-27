package ru.corvex.mailstorage.external;

import org.eclipse.angus.mail.iap.ConnectionException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.pop3.POP3Folder;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.StoreClosedException;
import jakarta.mail.Transport;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MailDateFormat;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import javax.net.ssl.SSLException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * Jakarta/Angus Mail клиент внешних ящиков. TLS hostname verification всегда включена;
 * IMAP читает через BODY.PEEK, поэтому сам факт сбора не помечает письма прочитанными.
 */
@Component
public class JakartaExternalMailClient implements ExternalMailClient {

    /** Ограничивает один IMAP FETCH служебных полей: вся папка одним ответом провайдерам тяжела. */
    private static final int FETCH_PROFILE_CHUNK_SIZE = 100;

    private final ExternalMailProperties props;
    private final ExternalMailEndpointPolicy endpointPolicy;

    public JakartaExternalMailClient(ExternalMailProperties props, ExternalMailEndpointPolicy endpointPolicy) {
        this.props = props;
        this.endpointPolicy = endpointPolicy;
    }

    @Override
    public TestResult testIncoming(AccountConfig config) throws ExternalMailClientException {
        try (IncomingSession session = openIncoming(config)) {
            List<RemoteFolderInfo> folders = session.listFolders();
            int inboxCount = folders.stream()
                    .filter(f -> "INBOX".equalsIgnoreCase(f.name()))
                    .mapToInt(RemoteFolderInfo::messageCount)
                    .findFirst().orElse(0);
            return new TestResult(true, "Входящее подключение успешно", folders.size(), inboxCount);
        }
    }

    @Override
    public TestResult testSmtp(AccountConfig config) throws ExternalMailClientException {
        if (!config.smtpEnabled() || config.smtpHost() == null || config.smtpHost().isBlank() || config.smtpPort() == null) {
            return new TestResult(false, "SMTP провайдера не настроен", 0, 0);
        }
        ExternalMailEndpointPolicy.Endpoint endpoint = validateSmtpEndpoint(config);
        Session session = Session.getInstance(smtpProperties(config, endpoint));
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(endpoint.host(), endpoint.port(), smtpUsername(config), config.secret());
            return new TestResult(true, "SMTP-аутентификация успешна", 0, 0);
        } catch (Exception e) {
            throw map(config, e);
        }
    }

    @Override
    public List<RemoteFolderInfo> listFolders(AccountConfig config) throws ExternalMailClientException {
        try (IncomingSession session = openIncoming(config)) {
            return session.listFolders();
        }
    }

    @Override
    public List<RemoteMessageEnvelope> listMessageEnvelopes(AccountConfig config, String remoteFolder, int limit)
            throws ExternalMailClientException {
        try (IncomingSession session = openIncoming(config)) {
            return session.listMessageEnvelopes(remoteFolder, limit);
        }
    }

    private List<RemoteMessageEnvelope> listMessageEnvelopes(AccountConfig config, Store store,
                                                              String remoteFolder, int limit)
            throws ExternalMailClientException {
        List<RemoteMessageEnvelope> result = new ArrayList<>();
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? props.safeBatchSize() : limit, 5000));
        fetchFolder(config, store, remoteFolder, 0, safeLimit, (envelope, source) -> {
            result.add(envelope);
            if (result.size() >= safeLimit) throw new StopFolderException(null);
            return HandleResult.SKIPPED;
        });
        return result;
    }

    @Override
    public FetchStats fetchFolder(AccountConfig config, String remoteFolder, long afterUid, int limit,
                                  MessageHandler handler) throws ExternalMailClientException {
        try (IncomingSession session = openIncoming(config)) {
            return session.fetchFolder(remoteFolder, afterUid, limit, handler);
        }
    }

    private FetchStats fetchFolder(AccountConfig config, Store store, String remoteFolder, long afterUid, int limit,
                                   MessageHandler handler) throws ExternalMailClientException {
        int safeLimit = Math.max(1, Math.min(limit <= 0 ? props.safeBatchSize() : limit, 5000));
        try {
            Folder folder = store.getFolder(remoteFolder);
            if (folder == null || !folder.exists()) {
                throw new ExternalMailClientException(ExternalMailClientException.Category.PROTOCOL, false,
                        "Удалённая папка не найдена: " + remoteFolder, null);
            }
            boolean readWrite = config.markSeenOnServer() || config.deleteAfterFetch();
            folder.open(readWrite ? Folder.READ_WRITE : Folder.READ_ONLY);
            boolean stopped = false;
            int seen = 0;
            int handled = 0;
            int queued = 0;
            try {
                List<Message> candidates = candidateMessages(folder, config, afterUid);
                for (int offset = 0; offset < candidates.size() && !stopped; offset += FETCH_PROFILE_CHUNK_SIZE) {
                    // The batch limit bounds newly downloaded/queued messages. Already-known
                    // POP3/IMAP envelopes are still scanned, otherwise an old mailbox larger
                    // than one batch would permanently hide newer messages at the end.
                    if (queued >= safeLimit) break;
                    List<Message> chunk = candidates.subList(offset,
                            Math.min(candidates.size(), offset + FETCH_PROFILE_CHUNK_SIZE));
                    fetchProfile(folder, chunk);
                    for (Message message : chunk) {
                        if (queued >= safeLimit) break;
                        seen++;
                        RemoteMessageEnvelope envelope = envelope(folder, message, config, remoteFolder);
                        HandleResult result;
                        try {
                            result = handler.handle(envelope, () -> openRaw(message));
                        } catch (StopFolderException stop) {
                            if (isConnectionLoss(stop)) {
                                throw map(config, stop);
                            }
                            stopped = true;
                            break;
                        } catch (Exception handlerError) {
                            throw new ExternalMailClientException(ExternalMailClientException.Category.PROTOCOL, true,
                                    "Обработка письма внешнего ящика остановлена: " + safeMessage(config, handlerError), handlerError);
                        }
                        handled++;
                        if (result == HandleResult.QUEUED) {
                            queued++;
                            if (config.markSeenOnServer()) {
                                message.setFlag(Flags.Flag.SEEN, true);
                            }
                        } else if (result == HandleResult.DELETE_ON_SERVER) {
                            message.setFlag(Flags.Flag.DELETED, true);
                        }
                    }
                }
                return new FetchStats(seen, handled, stopped);
            } finally {
                closeFolder(folder, config.deleteAfterFetch());
            }
        } catch (ExternalMailClientException e) {
            throw e;
        } catch (Exception e) {
            throw map(config, e);
        }
    }

    // ------------------------------------------------------------------ connection

    private record StoreConnection(Store store) implements AutoCloseable {
        @Override
        public void close() throws MessagingException {
            if (store != null && store.isConnected()) store.close();
        }
    }

    @Override
    public IncomingSession openIncoming(AccountConfig config) throws ExternalMailClientException {
        return new JakartaIncomingSession(config, connectIncoming(config));
    }

    private final class JakartaIncomingSession implements IncomingSession {
        private final AccountConfig config;
        private final StoreConnection connection;
        private boolean closed;

        private JakartaIncomingSession(AccountConfig config, StoreConnection connection) {
            this.config = config;
            this.connection = connection;
        }

        @Override
        public List<RemoteFolderInfo> listFolders() throws ExternalMailClientException {
            try {
                return JakartaExternalMailClient.this.listFolders(store(), config);
            } catch (ExternalMailClientException e) {
                throw e;
            } catch (Exception e) {
                throw map(config, e);
            }
        }

        @Override
        public List<RemoteMessageEnvelope> listMessageEnvelopes(String remoteFolder, int limit)
                throws ExternalMailClientException {
            return JakartaExternalMailClient.this.listMessageEnvelopes(
                    config, store(), remoteFolder, limit);
        }

        @Override
        public FetchStats fetchFolder(String remoteFolder, long afterUid, int limit, MessageHandler handler)
                throws ExternalMailClientException {
            return JakartaExternalMailClient.this.fetchFolder(
                    config, store(), remoteFolder, afterUid, limit, handler);
        }

        private Store store() throws ExternalMailClientException {
            if (closed) {
                throw new ExternalMailClientException(ExternalMailClientException.Category.CONFIG, false,
                        "Входящее соединение уже закрыто", null);
            }
            return connection.store();
        }

        @Override
        public void close() {
            closed = true;
            try {
                connection.close();
            } catch (MessagingException ignored) {
                // Результат синхронизации уже зафиксирован; ошибка закрытия не отменяет его.
            }
        }
    }

    private StoreConnection connectIncoming(AccountConfig config) throws ExternalMailClientException {
        ExternalMailEndpointPolicy.Endpoint endpoint;
        try {
            endpoint = endpointPolicy.validate(config.incomingHost(), config.incomingPort(), config.incomingSecurity(),
                    config.provider() != ExternalMailAccount.Provider.CUSTOM);
        } catch (ResponseStatusException e) {
            throw endpointFailure(e);
        }
        Session session = Session.getInstance(incomingProperties(config, endpoint));
        try {
            Store store = session.getStore(storeProtocol(config));
            store.connect(endpoint.host(), endpoint.port(), config.incomingUsername(), config.secret());
            return new StoreConnection(store);
        } catch (Exception e) {
            throw map(config, e);
        }
    }

    private Properties incomingProperties(AccountConfig config, ExternalMailEndpointPolicy.Endpoint endpoint) {
        Properties p = new Properties();
        boolean imap = config.incomingProtocol() == ExternalMailAccount.Protocol.IMAP;
        String prefix = imap ? "mail.imap" : "mail.pop3";
        p.put("mail.store.protocol", storeProtocol(config));
        p.put(prefix + ".host", endpoint.host());
        p.put(prefix + ".port", String.valueOf(endpoint.port()));
        p.put(prefix + ".connectiontimeout", String.valueOf(timeoutMillis(props.connectTimeoutSeconds())));
        p.put(prefix + ".timeout", String.valueOf(timeoutMillis(props.readTimeoutSeconds())));
        p.put(prefix + ".writetimeout", String.valueOf(timeoutMillis(props.readTimeoutSeconds())));
        p.put(prefix + ".ssl.checkserveridentity", "true");
        if (config.incomingSecurity() == ExternalMailAccount.Security.STARTTLS) {
            p.put(prefix + ".starttls.enable", "true");
            p.put(prefix + ".starttls.required", "true");
        }
        if (imap) {
            p.put("mail.imap.peek", "true");
            p.put("mail.imap.connectionpoolsize", "1");
            p.put("mail.imap.fetchsize", "1048576");
        }
        return p;
    }

    private Properties smtpProperties(AccountConfig config, ExternalMailEndpointPolicy.Endpoint endpoint) {
        Properties p = new Properties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.host", endpoint.host());
        p.put("mail.smtp.port", String.valueOf(endpoint.port()));
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMillis(props.connectTimeoutSeconds())));
        p.put("mail.smtp.timeout", String.valueOf(timeoutMillis(props.readTimeoutSeconds())));
        p.put("mail.smtp.writetimeout", String.valueOf(timeoutMillis(props.readTimeoutSeconds())));
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        if (config.smtpSecurity() == ExternalMailAccount.Security.SSL_TLS) {
            p.put("mail.smtp.ssl.enable", "true");
        } else if (config.smtpSecurity() == ExternalMailAccount.Security.STARTTLS) {
            p.put("mail.smtp.starttls.enable", "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return p;
    }

    private ExternalMailEndpointPolicy.Endpoint validateSmtpEndpoint(AccountConfig config) throws ExternalMailClientException {
        try {
            return endpointPolicy.validate(config.smtpHost(), config.smtpPort(), config.smtpSecurity(),
                    config.provider() != ExternalMailAccount.Provider.CUSTOM);
        } catch (ResponseStatusException e) {
            throw endpointFailure(e);
        }
    }

    private static ExternalMailClientException endpointFailure(ResponseStatusException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof UnknownHostException) {
                return new ExternalMailClientException(ExternalMailClientException.Category.NETWORK, true,
                        e.getReason(), e);
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return new ExternalMailClientException(ExternalMailClientException.Category.CONFIG, false,
                e.getReason(), e);
    }

    private static String storeProtocol(AccountConfig config) {
        boolean ssl = config.incomingSecurity() == ExternalMailAccount.Security.SSL_TLS;
        if (config.incomingProtocol() == ExternalMailAccount.Protocol.POP3) {
            return ssl ? "pop3s" : "pop3";
        }
        return ssl ? "imaps" : "imap";
    }

    private static String smtpUsername(AccountConfig config) {
        return config.smtpUsername() == null || config.smtpUsername().isBlank()
                ? config.incomingUsername() : config.smtpUsername();
    }

    private static int timeoutMillis(int seconds) {
        return Math.max(1, Math.min(seconds, 300)) * 1000;
    }

    // ------------------------------------------------------------------ folders/messages

    private List<RemoteFolderInfo> listFolders(Store store, AccountConfig config) throws MessagingException {
        if (config.incomingProtocol() == ExternalMailAccount.Protocol.POP3) {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                return List.of(new RemoteFolderInfo("INBOX", "/", null, 0, inbox.getMessageCount(), Set.of(), "INBOX"));
            } finally {
                closeFolder(inbox, false);
            }
        }
        Folder[] remote = store.getDefaultFolder().list("*");
        List<RemoteFolderInfo> result = new ArrayList<>();
        for (Folder folder : remote) {
            if (result.size() >= props.safeMaxFolders()) break;
            if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
            String delimiter = delimiter(folder);
            Set<String> attributes = attributes(folder);
            Long UIDValidity = null;
            long uidNext = 0;
            int count = 0;
            try {
                // Не открываем каждую папку SELECT-ом только ради счётчиков: у IMAPFolder эти
                // вызовы на закрытой папке используют STATUS, что заметно легче для провайдера.
                count = Math.max(0, folder.getMessageCount());
                if (folder instanceof UIDFolder UIDFolder) {
                    UIDValidity = UIDFolder.getUIDValidity();
                    uidNext = Math.max(0, UIDFolder.getUIDNext());
                }
            } catch (MessagingException ignored) {
                // \Noselect и служебные папки некоторых провайдеров нельзя открыть: они не источник почты.
                continue;
            }
            result.add(new RemoteFolderInfo(folder.getFullName(), delimiter, UIDValidity, uidNext, count,
                    attributes, specialUse(folder.getFullName(), delimiter, attributes)));
        }
        result.sort(Comparator.comparing((RemoteFolderInfo f) -> !"INBOX".equalsIgnoreCase(f.name()))
                .thenComparing(RemoteFolderInfo::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private List<Message> candidateMessages(Folder folder, AccountConfig config, long afterUid)
            throws MessagingException {
        if (config.incomingProtocol() == ExternalMailAccount.Protocol.IMAP
                && folder instanceof UIDFolder UIDFolder) {
            if (afterUid >= UIDFolder.MAXUID) return List.of();
            long startUid = Math.max(1L, afterUid + 1L);
            // MAXUID (не LASTUID): LASTUID вернул бы последнее письмо даже при пустом диапазоне.
            Message[] messages = UIDFolder.getMessagesByUID(startUid, UIDFolder.MAXUID);
            if (messages == null || messages.length == 0) return List.of();
            List<Message> result = new ArrayList<>(messages.length);
            for (Message message : messages) {
                if (message != null) result.add(message);
            }
            result.sort((a, b) -> {
                try {
                    return Long.compare(UIDFolder.getUID(a), UIDFolder.getUID(b));
                } catch (MessagingException e) {
                    return Integer.compare(a.getMessageNumber(), b.getMessageNumber());
                }
            });
            return result;
        }

        Message[] messages = folder.getMessages();
        if (messages == null || messages.length == 0) return List.of();
        List<Message> result = new ArrayList<>(messages.length);
        for (Message message : messages) {
            if (message != null) result.add(message);
        }
        return result;
    }

    private void fetchProfile(Folder folder, List<Message> messages) throws MessagingException {
        if (messages.isEmpty()) return;
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.FLAGS);
        if (folder instanceof UIDFolder) {
            profile.add(UIDFolder.FetchProfileItem.UID);
        }
        if (folder instanceof IMAPFolder) {
            // Не используем ENVELOPE/BODYSTRUCTURE: Angus после проигнорированного ответа NO
            // иначе скрыто повторяет FETCH одного письма из getReceivedDate()/getFrom().
            profile.add(IMAPFolder.FetchProfileItem.INTERNALDATE);
            profile.add(IMAPFolder.FetchProfileItem.SIZE);
            profile.add("Message-ID");
            profile.add("From");
            profile.add("Sender");
            profile.add("Date");
        } else {
            // POP3 ENVELOPE означает TOP + LIST и заранее заполняет заголовки и размер.
            profile.add(FetchProfile.Item.ENVELOPE);
        }
        folder.fetch(messages.toArray(new Message[0]), profile);
    }

    private RemoteMessageEnvelope envelope(Folder folder, Message message, AccountConfig config, String remoteFolder)
            throws MessagingException, ExternalMailClientException {
        Long UIDValidity = null;
        String remoteUid;
        if (config.incomingProtocol() == ExternalMailAccount.Protocol.POP3) {
            if (!(folder instanceof POP3Folder pop3)) {
                throw new ExternalMailClientException(ExternalMailClientException.Category.PROTOCOL, false,
                        "POP3-папка не поддерживает UIDL", null);
            }
            remoteUid = pop3.getUID(message);
            if (remoteUid == null || remoteUid.isBlank()) {
                throw new ExternalMailClientException(ExternalMailClientException.Category.PROTOCOL, false,
                        "POP3-сервер не возвращает UIDL; безопасная дедупликация невозможна", null);
            }
        } else if (folder instanceof UIDFolder UIDFolder) {
            UIDValidity = UIDFolder.getUIDValidity();
            remoteUid = String.valueOf(UIDFolder.getUID(message));
        } else {
            remoteUid = String.valueOf(message.getMessageNumber());
        }
        Flags flags = message.getFlags();
        boolean imap = folder instanceof IMAPFolder;
        Instant internal = imap ? imapInternalDate(message) : legacyInternalDate(message);
        long size = imap ? imapSize(message) : Math.max(0, message.getSize());
        String from = imap ? firstAddressHeader(message) : firstAddress(message.getFrom());
        return new RemoteMessageEnvelope(
                remoteFolder,
                remoteUid,
                UIDValidity,
                firstHeader(message, "Message-ID"),
                from,
                internal,
                size,
                flags.contains(Flags.Flag.SEEN),
                flags.contains(Flags.Flag.ANSWERED),
                flags.contains(Flags.Flag.FLAGGED),
                flags.contains(Flags.Flag.DRAFT),
                flags.contains(Flags.Flag.DELETED));
    }

    private InputStream openRaw(Message message) throws IOException {
        if (!(message instanceof MimeMessage mime)) {
            throw new IOException("Удалённое письмо не является MIMEMessage");
        }
        Path tmp = Files.createTempFile("corvex-external-mail-", ".eml");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            mime.writeTo(out);
        } catch (MessagingException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("Не удалось прочитать сырое письмо внешнего ящика", e);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return new DeleteOnCloseInputStream(Files.newInputStream(tmp), tmp);
    }

    private static final class DeleteOnCloseInputStream extends FilterInputStream {
        private final Path path;

        private DeleteOnCloseInputStream(InputStream in, Path path) {
            super(in);
            this.path = path;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void closeFolder(Folder folder, boolean expunge) {
        if (folder == null || !folder.isOpen()) return;
        try {
            folder.close(expunge);
        } catch (MessagingException ignored) {
            // Соединение закроется вместе со Store.
        }
    }

    private static String delimiter(Folder folder) {
        try {
            char separator = folder.getSeparator();
            return separator == 0 ? "/" : String.valueOf(separator);
        } catch (MessagingException e) {
            return "/";
        }
    }

    private static Set<String> attributes(Folder folder) {
        if (!(folder instanceof IMAPFolder imap)) return Set.of();
        try {
            String[] raw = imap.getAttributes();
            if (raw == null) return Set.of();
            Set<String> out = new LinkedHashSet<>();
            for (String attr : raw) out.add(attr.toLowerCase(Locale.ROOT));
            return out;
        } catch (MessagingException e) {
            return Set.of();
        }
    }

    static String specialUse(String fullName, String delimiter, Set<String> attributes) {
        if (attributes.contains("\\inbox") || "INBOX".equalsIgnoreCase(fullName)) return "INBOX";
        if (attributes.contains("\\sent")) return "SENT";
        if (attributes.contains("\\drafts")) return "DRAFTS";
        if (attributes.contains("\\trash")) return "TRASH";
        if (attributes.contains("\\junk")) return "SPAM";
        if (attributes.contains("\\archive") || attributes.contains("\\all")) return "ARCHIVE";
        String last = lastSegment(fullName, delimiter).toLowerCase(Locale.ROOT).trim();
        return switch (last) {
            case "sent", "sent messages", "sent items", "отправленные" -> "SENT";
            case "drafts", "draft", "черновики" -> "DRAFTS";
            case "trash", "deleted", "deleted messages", "deleted items", "корзина", "удалённые", "удаленные" -> "TRASH";
            case "junk", "spam", "bulk mail", "спам" -> "SPAM";
            case "archive", "archives", "all mail", "архив", "вся почта" -> "ARCHIVE";
            default -> null;
        };
    }

    private static String lastSegment(String fullName, String delimiter) {
        if (fullName == null || fullName.isBlank()) return "";
        String d = delimiter == null || delimiter.isEmpty() ? "/" : delimiter;
        int idx = fullName.lastIndexOf(d);
        return idx >= 0 ? fullName.substring(idx + d.length()) : fullName;
    }

    private static Instant legacyInternalDate(Message message) throws MessagingException {
        var received = message.getReceivedDate();
        if (received != null) return received.toInstant();
        var sent = message.getSentDate();
        return sent != null ? sent.toInstant() : Instant.now();
    }

    private static Instant imapInternalDate(Message message) throws MessagingException {
        try {
            var received = message.getReceivedDate();
            if (received != null) return received.toInstant();
        } catch (MessagingException ignored) {
            // IMAPFolder.fetch() может проглотить NO по RFC 2180. Не повторяем падение папки
            // из-за необязательных метаданных: ниже используется обычный заголовок Date.
        }
        String header = firstHeader(message, "Date");
        if (header != null) {
            try {
                return new MailDateFormat().parse(header).toInstant();
            } catch (ParseException ignored) {
                // Фактическая дата будет восстановлена MIME-парсером после скачивания письма.
            }
        }
        return Instant.now();
    }

    private static long imapSize(Message message) {
        try {
            return Math.max(0, message.getSize());
        } catch (MessagingException ignored) {
            // Для доставки используется размер фактически сохранённого raw .eml, а не этот hint.
            return 0;
        }
    }

    private static String firstAddressHeader(Message message) throws MessagingException {
        String header = firstHeader(message, "From");
        if (header == null) header = firstHeader(message, "Sender");
        if (header == null) return null;
        try {
            return firstAddress(InternetAddress.parseHeader(header, false));
        } catch (AddressException ignored) {
            // Невалидный From не должен блокировать сбор: отправитель разберётся из raw MIME.
            return null;
        }
    }

    private static String firstHeader(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        if (values == null || values.length == 0 || values[0] == null) return null;
        String value = values[0].trim();
        return value.length() <= 998 ? value : value.substring(0, 998);
    }

    private static String firstAddress(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        Address first = addresses[0];
        if (first instanceof InternetAddress internet) return internet.getAddress();
        return first.toString();
    }

    // ------------------------------------------------------------------ errors

    private static boolean isConnectionLoss(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof FolderClosedException || current instanceof StoreClosedException
                    || current instanceof ConnectionException) {
                return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private ExternalMailClientException map(AccountConfig config, Exception e) {
        Throwable root = rootCause(e);
        String message = safeMessage(config, root);
        if (e instanceof AuthenticationFailedException || root instanceof AuthenticationFailedException
                || message.toLowerCase(Locale.ROOT).contains("authentication failed")
                || message.toLowerCase(Locale.ROOT).contains("invalid credentials")) {
            return new ExternalMailClientException(ExternalMailClientException.Category.AUTH, false,
                    "Ошибка аутентификации внешнего ящика: проверьте пароль приложения", e);
        }
        if (root instanceof SSLException || message.toLowerCase(Locale.ROOT).contains("ssl")
                || message.toLowerCase(Locale.ROOT).contains("certificate")) {
            return new ExternalMailClientException(ExternalMailClientException.Category.TLS, false,
                    "Ошибка TLS внешнего почтового сервера: " + message, e);
        }
        if (root instanceof UnknownHostException || root instanceof ConnectException || root instanceof SocketTimeoutException
                || e instanceof ConnectionException || root instanceof ConnectionException) {
            return new ExternalMailClientException(ExternalMailClientException.Category.NETWORK, true,
                    "Внешний почтовый сервер недоступен: " + message, e);
        }
        if (e instanceof FolderClosedException || e instanceof StoreClosedException
                || root instanceof FolderClosedException || root instanceof StoreClosedException) {
            return new ExternalMailClientException(ExternalMailClientException.Category.NETWORK, true,
                    "Соединение с внешним почтовым сервером потеряно: " + message, e);
        }
        return new ExternalMailClientException(ExternalMailClientException.Category.PROTOCOL, true,
                "Ошибка внешнего почтового сервера: " + message, e);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
            return messaging.getNextException();
        }
        return current;
    }

    private static String safeMessage(AccountConfig config, Throwable e) {
        String message = e == null || e.getMessage() == null ? "неизвестная ошибка" : e.getMessage();
        if (config != null && config.secret() != null && !config.secret().isBlank()) {
            message = message.replace(config.secret(), "***");
        }
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
