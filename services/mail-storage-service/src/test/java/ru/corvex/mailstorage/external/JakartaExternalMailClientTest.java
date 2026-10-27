package ru.corvex.mailstorage.external;

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
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.pop3.POP3Folder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import javax.net.ssl.SSLHandshakeException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JakartaExternalMailClientTest {

    private ExternalMailProperties props;
    private ExternalMailEndpointPolicy endpointPolicy;
    private JakartaExternalMailClient client;
    private final AtomicReference<ExternalMailEndpointPolicy.Endpoint> endpointOverride = new AtomicReference<>();
    private final AtomicReference<ResponseStatusException> endpointFailure = new AtomicReference<>();
    private FakePop3Server pop3;
    private FakeSmtpServer smtp;

    @BeforeEach
    void setUp() {
        props = new ExternalMailProperties(true, true, true, true, 10, 2, 10, 2, 3, 1);
        endpointPolicy = mock(ExternalMailEndpointPolicy.class);
        when(endpointPolicy.validate(anyString(), anyInt(), any(), anyBoolean())).thenAnswer(inv -> {
            ResponseStatusException failure = endpointFailure.get();
            if (failure != null) throw failure;
            ExternalMailEndpointPolicy.Endpoint override = endpointOverride.get();
            if (override != null) return override;
            return new ExternalMailEndpointPolicy.Endpoint(
                    inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), List.of("127.0.0.1"));
        });
        client = new JakartaExternalMailClient(props, endpointPolicy);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (pop3 != null) pop3.close();
        if (smtp != null) smtp.close();
    }

    @Test
    void smtpTestShortCircuitsWhenProviderSmtpIsNotConfigured() throws Exception {
        var disabled = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                false, "smtp.test", 587, "secret");
        assertFalse(client.testSmtp(disabled).ok());

        var blankHost = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                true, " ", 587, "secret");
        assertFalse(client.testSmtp(blankHost).ok());

        var noPort = new ExternalMailClient.AccountConfig(
                UUID.randomUUID(), UUID.randomUUID(), "a@mail.test", "A", ExternalMailAccount.Provider.CUSTOM,
                ExternalMailAccount.Protocol.IMAP, "imap.test", 993, ExternalMailAccount.Security.SSL_TLS,
                "user", "secret", true, "smtp.test", null, ExternalMailAccount.Security.SSL_TLS,
                "user", false, false);
        assertFalse(client.testSmtp(noPort).ok());
    }

    @Test
    void endpointPolicyFailureBecomesPermanentConfigError() {
        endpointFailure.set(new ResponseStatusException(HttpStatus.BAD_REQUEST, "endpoint blocked"));
        var config = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                true, "smtp.test", 587, "secret");

        var smtpError = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.testSmtp(config));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.CONFIG, smtpError.category());
        assertFalse(smtpError.temporary());

        var incomingError = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.listFolders(config));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.CONFIG, incomingError.category());
    }

    @Test
    void endpointDnsFailureBecomesTemporaryNetworkError() {
        endpointFailure.set(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Не удалось разрешить DNS-имя почтового сервера: imap.test",
                new UnknownHostException("imap.test")));
        var config = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                true, "smtp.test", 587, "secret");

        var incomingError = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.listFolders(config));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.NETWORK, incomingError.category());
        assertTrue(incomingError.temporary());

        var smtpError = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.testSmtp(config));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.NETWORK, smtpError.category());
        assertTrue(smtpError.temporary());
    }

    @Test
    void smtpAuthenticationWorksAndFailuresAreClassified() throws Exception {
        smtp = new FakeSmtpServer("user", "secret");
        smtp.start();
        endpointOverride.set(endpoint("127.0.0.1", smtp.port(), ExternalMailAccount.Security.PLAIN));
        var good = withSmtpSecurity(config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                true, "smtp.test", 587, "secret"), ExternalMailAccount.Security.PLAIN);

        assertTrue(client.testSmtp(good).ok());
        assertTrue(smtp.commands.stream().anyMatch(c -> c.startsWith("AUTH ")));

        var bad = withSmtpSecurity(config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                true, "smtp.test", 587, "wrong"), ExternalMailAccount.Security.PLAIN);
        var error = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.testSmtp(bad));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.AUTH, error.category());
        assertFalse(error.temporary());
        assertFalse(error.getMessage().contains("wrong"));
    }

    @Test
    void closedSmtpPortBecomesTemporaryNetworkError() {
        endpointOverride.set(endpoint("127.0.0.1", 1, ExternalMailAccount.Security.PLAIN));
        var config = withSmtpSecurity(config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                true, "smtp.test", 587, "secret"), ExternalMailAccount.Security.PLAIN);

        var error = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.testSmtp(config));

        assertEquals(ExternalMailClient.ExternalMailClientException.Category.NETWORK, error.category());
        assertTrue(error.temporary());
    }

    @Test
    void pop3PublicFlowTestsListsFetchesQueuesAndDeletes() throws Exception {
        pop3 = new FakePop3Server("user", "secret", List.of(rawMessage("one", "First"), rawMessage("two", "Second")));
        pop3.start();
        endpointOverride.set(endpoint("127.0.0.1", pop3.port(), ExternalMailAccount.Security.PLAIN));
        var config = pop3Config("secret", false, true);

        var test = client.testIncoming(config);
        assertTrue(test.ok());
        assertEquals(1, test.folderCount());
        assertEquals(2, test.inboxMessageCount());

        var folders = client.listFolders(config);
        assertEquals("INBOX", folders.get(0).specialUse());
        assertEquals(2, folders.get(0).messageCount());

        List<String> bodies = new ArrayList<>();
        List<String> uids = new ArrayList<>();
        var stats = client.fetchFolder(config, "INBOX", 999, 10, (envelope, source) -> {
            uids.add(envelope.remoteUid());
            try (InputStream in = source.open()) {
                bodies.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            return envelope.remoteUid().equals("uid-one")
                    ? ExternalMailClient.HandleResult.DELETE_ON_SERVER
                    : ExternalMailClient.HandleResult.QUEUED;
        });

        assertEquals(2, stats.seenByClient());
        assertEquals(2, stats.handled());
        assertFalse(stats.stopped());
        assertEquals(List.of("uid-one", "uid-two"), uids);
        assertTrue(bodies.get(0).contains("First"));
        assertTrue(pop3.commands.stream().anyMatch(c -> c.equals("DELE 1")),
                "DELETE_ON_SERVER при deleteAfterFetch должен дойти до POP3 DELE при закрытии");
    }

    @Test
    void pop3ListEnvelopesStopsAtLimitAndHandlerFailuresBecomeTemporary() throws Exception {
        pop3 = new FakePop3Server("user", "secret", List.of(rawMessage("one", "First"), rawMessage("two", "Second")));
        pop3.start();
        endpointOverride.set(endpoint("127.0.0.1", pop3.port(), ExternalMailAccount.Security.PLAIN));
        var config = pop3Config("secret", false, false);

        var envelopes = client.listMessageEnvelopes(config, "INBOX", 1);
        assertEquals(1, envelopes.size());
        assertEquals("uid-one", envelopes.get(0).remoteUid());
        assertEquals("<one@mail.test>", envelopes.get(0).messageIdHeader());
        assertEquals("sender@partner.test", envelopes.get(0).from());

        var stats = client.fetchFolder(config, "INBOX", 0, 10, (envelope, source) -> {
            throw new ExternalMailClient.StopFolderException(null);
        });
        assertEquals(1, stats.seenByClient());
        assertEquals(0, stats.handled());
        assertTrue(stats.stopped());

        var error = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.fetchFolder(config, "INBOX", 0, 10, (envelope, source) -> {
                    throw new IllegalStateException("handler failed with secret");
                }));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.PROTOCOL, error.category());
        assertTrue(error.temporary());
        assertFalse(error.getMessage().contains("secret"), "секрет должен маскироваться в ошибках обработчика");
    }

    @Test
    void pop3MissingFolderAndAuthenticationErrorsAreClassified() throws Exception {
        pop3 = new FakePop3Server("user", "secret", List.of(rawMessage("one", "First")));
        pop3.start();
        endpointOverride.set(endpoint("127.0.0.1", pop3.port(), ExternalMailAccount.Security.PLAIN));

        var missing = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.fetchFolder(pop3Config("secret", false, false), "Missing", 0, 10,
                        (envelope, source) -> ExternalMailClient.HandleResult.SKIPPED));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.PROTOCOL, missing.category());
        assertFalse(missing.temporary());

        var auth = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> client.testIncoming(pop3Config("wrong", false, false)));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.AUTH, auth.category());
    }

    @Test
    void propertiesUseExpectedProtocolsAndTlsSettings() throws Exception {
        var imapConfig = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                true, "smtp.test", 465, "secret");
        Properties imap = invoke("incomingProperties",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, ExternalMailEndpointPolicy.Endpoint.class},
                imapConfig, endpoint("imap.test", 993, ExternalMailAccount.Security.SSL_TLS));
        assertEquals("imaps", imap.getProperty("mail.store.protocol"));
        assertEquals("imap.test", imap.getProperty("mail.imap.host"));
        assertEquals("2000", imap.getProperty("mail.imap.connectiontimeout"));
        assertEquals("3000", imap.getProperty("mail.imap.timeout"));
        assertEquals("true", imap.getProperty("mail.imap.ssl.checkserveridentity"));
        assertEquals("true", imap.getProperty("mail.imap.peek"));
        assertNull(imap.getProperty("mail.imap.starttls.enable"));

        var popConfig = config(ExternalMailAccount.Protocol.POP3, ExternalMailAccount.Security.STARTTLS,
                true, "smtp.test", 587, "secret");
        Properties pop = invoke("incomingProperties",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, ExternalMailEndpointPolicy.Endpoint.class},
                popConfig, endpoint("pop.test", 110, ExternalMailAccount.Security.STARTTLS));
        assertEquals("pop3", pop.getProperty("mail.store.protocol"));
        assertEquals("true", pop.getProperty("mail.pop3.starttls.enable"));
        assertEquals("true", pop.getProperty("mail.pop3.starttls.required"));

        Properties smtpSsl = invoke("smtpProperties",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, ExternalMailEndpointPolicy.Endpoint.class},
                withSmtpSecurity(imapConfig, ExternalMailAccount.Security.SSL_TLS),
                endpoint("smtp.test", 465, ExternalMailAccount.Security.SSL_TLS));
        assertEquals("true", smtpSsl.getProperty("mail.smtp.ssl.enable"));

        Properties smtpStartTls = invoke("smtpProperties",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, ExternalMailEndpointPolicy.Endpoint.class},
                withSmtpSecurity(imapConfig, ExternalMailAccount.Security.STARTTLS),
                endpoint("smtp.test", 587, ExternalMailAccount.Security.STARTTLS));
        assertEquals("true", smtpStartTls.getProperty("mail.smtp.starttls.required"));

        Properties smtpPlain = invoke("smtpProperties",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, ExternalMailEndpointPolicy.Endpoint.class},
                withSmtpSecurity(imapConfig, ExternalMailAccount.Security.PLAIN),
                endpoint("smtp.test", 25, ExternalMailAccount.Security.PLAIN));
        assertNull(smtpPlain.getProperty("mail.smtp.ssl.enable"));
        assertNull(smtpPlain.getProperty("mail.smtp.starttls.enable"));
    }

    @Test
    void staticConnectionHelpersCoverAllProtocolCombinations() throws Exception {
        assertEquals("imaps", invokeStatic("storeProtocol",
                new Class<?>[]{ExternalMailClient.AccountConfig.class},
                config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS, false, null, null, "s")));
        assertEquals("imap", invokeStatic("storeProtocol",
                new Class<?>[]{ExternalMailClient.AccountConfig.class},
                config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.PLAIN, false, null, null, "s")));
        assertEquals("pop3s", invokeStatic("storeProtocol",
                new Class<?>[]{ExternalMailClient.AccountConfig.class},
                config(ExternalMailAccount.Protocol.POP3, ExternalMailAccount.Security.SSL_TLS, false, null, null, "s")));
        assertEquals("pop3", invokeStatic("storeProtocol",
                new Class<?>[]{ExternalMailClient.AccountConfig.class},
                config(ExternalMailAccount.Protocol.POP3, ExternalMailAccount.Security.STARTTLS, false, null, null, "s")));

        var explicit = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.PLAIN, true, "s", 1, "x");
        assertEquals("user", invokeStatic("smtpUsername",
                new Class<?>[]{ExternalMailClient.AccountConfig.class}, explicit));
        var blankSmtpUser = new ExternalMailClient.AccountConfig(
                explicit.accountId(), explicit.ownerId(), explicit.emailAddress(), explicit.displayName(),
                explicit.provider(), explicit.incomingProtocol(), explicit.incomingHost(), explicit.incomingPort(),
                explicit.incomingSecurity(), "incoming-user", "secret", true, "smtp", 1,
                ExternalMailAccount.Security.PLAIN, " ", false, false);
        assertEquals("incoming-user", invokeStatic("smtpUsername",
                new Class<?>[]{ExternalMailClient.AccountConfig.class}, blankSmtpUser));
        Integer minTimeout = invokeStatic("timeoutMillis", new Class<?>[]{int.class}, 0);
        Integer maxTimeout = invokeStatic("timeoutMillis", new Class<?>[]{int.class}, 999);
        assertEquals(1000, minTimeout.intValue());
        assertEquals(300_000, maxTimeout.intValue());
    }

    @Test
    void reflectedPop3FolderListingUsesInboxOnly() throws Exception {
        Store store = mock(Store.class);
        Folder inbox = mock(Folder.class);
        when(store.getFolder("INBOX")).thenReturn(inbox);
        when(inbox.getMessageCount()).thenReturn(9);
        when(inbox.isOpen()).thenReturn(true);

        List<ExternalMailClient.RemoteFolderInfo> folders = invoke("listFolders",
                new Class<?>[]{Store.class, ExternalMailClient.AccountConfig.class}, store, pop3Config("secret", false, false));

        assertEquals(1, folders.size());
        assertEquals(9, folders.get(0).messageCount());
        verify(inbox).open(Folder.READ_ONLY);
        verify(inbox).close(false);
    }

    @Test
    void reflectedImapFolderListingSkipsContainersAndBrokenFoldersAndSortsInboxFirst() throws Exception {
        Store store = mock(Store.class);
        Folder root = mock(Folder.class);
        when(store.getDefaultFolder()).thenReturn(root);

        Folder container = genericFolder("Container", Folder.HOLDS_FOLDERS, '/', 0);
        IMAPFolder archive = imapFolder("INBOX.Archive", '.', new String[0], 4, 22L, 30L);
        IMAPFolder inbox = imapFolder("INBOX", '.', new String[]{"\\HasNoChildren"}, 2, 11L, 20L);
        IMAPFolder broken = imapFolder("Broken", '.', new String[0], 1, 1L, 2L);
        doThrow(new MessagingException("STATUS failed")).when(broken).getMessageCount();
        when(root.list("*")).thenReturn(new Folder[]{container, archive, broken, inbox});

        List<ExternalMailClient.RemoteFolderInfo> folders = invoke("listFolders",
                new Class<?>[]{Store.class, ExternalMailClient.AccountConfig.class}, store,
                config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS, false, null, null, "s"));

        assertEquals(List.of("INBOX", "INBOX.Archive"), folders.stream().map(f -> f.name()).toList());
        assertEquals(11L, folders.get(0).UIDValidity());
        assertEquals(20L, folders.get(0).uidNext());
        assertEquals("ARCHIVE", folders.get(1).specialUse());
        verify(inbox, org.mockito.Mockito.never()).open(anyInt());
        verify(archive, org.mockito.Mockito.never()).open(anyInt());
    }

    @Test
    void candidateMessagesUsesUidRangeForImapAndLeavesPop3Order() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        Message one = message(1);
        Message two = message(2);
        Message three = message(3);
        when(folder.getMessagesByUID(2L, UIDFolder.MAXUID)).thenReturn(new Message[]{three, two});
        when(folder.getUID(one)).thenReturn(1L);
        when(folder.getUID(two)).thenReturn(2L);
        when(folder.getUID(three)).thenReturn(3L);
        var imapConfig = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                false, null, null, "s");

        List<Message> ordered = invoke("candidateMessages",
                new Class<?>[]{Folder.class, ExternalMailClient.AccountConfig.class, long.class},
                folder, imapConfig, 1L);
        assertEquals(List.of(two, three), ordered);
        verify(folder, org.mockito.Mockito.never()).getMessages();

        Folder popFolder = mock(Folder.class);
        when(popFolder.getMessages()).thenReturn(new Message[]{three, one});
        List<Message> popOrdered = invoke("candidateMessages",
                new Class<?>[]{Folder.class, ExternalMailClient.AccountConfig.class, long.class},
                popFolder, pop3Config("s", false, false), 999L);
        assertEquals(List.of(three, one), popOrdered);

        when(folder.getMessagesByUID(1L, UIDFolder.MAXUID)).thenReturn(new Message[0]);
        List<Message> empty = invoke("candidateMessages",
                new Class<?>[]{Folder.class, ExternalMailClient.AccountConfig.class, long.class},
                folder, imapConfig, 0L);
        assertTrue(empty.isEmpty());
    }

    @Test
    void fetchProfileUsesEnvelopeFlagsAndUidWithoutBodystructure() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        Message one = message(1);

        invoke("fetchProfile", new Class<?>[]{Folder.class, List.class}, folder, List.of(one));

        ArgumentCaptor<FetchProfile> captor = ArgumentCaptor.forClass(FetchProfile.class);
        verify(folder).fetch(any(Message[].class), captor.capture());
        FetchProfile profile = captor.getValue();
        assertTrue(profile.contains(FetchProfile.Item.ENVELOPE));
        assertTrue(profile.contains(FetchProfile.Item.FLAGS));
        assertTrue(profile.contains(UIDFolder.FetchProfileItem.UID));
        assertFalse(profile.contains(FetchProfile.Item.CONTENT_INFO));
    }

    @Test
    void envelopeCoversImapUidFallbackGenericAddressAndDates() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        Message message = message(5);
        Flags flags = new Flags();
        flags.add(Flags.Flag.SEEN);
        flags.add(Flags.Flag.ANSWERED);
        flags.add(Flags.Flag.FLAGGED);
        flags.add(Flags.Flag.DRAFT);
        flags.add(Flags.Flag.DELETED);
        when(folder.getUIDValidity()).thenReturn(77L);
        when(folder.getUID(message)).thenReturn(42L);
        when(message.getFlags()).thenReturn(flags);
        when(message.getHeader("Message-ID")).thenReturn(new String[]{"<" + "x".repeat(1100) + ">"});
        when(message.getFrom()).thenReturn(new Address[]{new InternetAddress("sender@partner.test")});
        Date sent = Date.from(Instant.parse("2026-02-03T04:05:06Z"));
        when(message.getReceivedDate()).thenReturn(null);
        when(message.getSentDate()).thenReturn(sent);
        when(message.getSize()).thenReturn(-5);

        ExternalMailClient.RemoteMessageEnvelope env = invoke("envelope",
                new Class<?>[]{Folder.class, Message.class, ExternalMailClient.AccountConfig.class, String.class},
                folder, message,
                config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS, false, null, null, "s"),
                "INBOX");

        assertEquals("42", env.remoteUid());
        assertEquals(77L, env.UIDValidity());
        assertEquals(998, env.messageIdHeader().length());
        assertEquals(sent.toInstant(), env.internalDate());
        assertEquals(0, env.sizeBytes());
        assertTrue(env.seen() && env.answered() && env.flagged() && env.draft() && env.deleted());

        Folder generic = mock(Folder.class);
        Message genericMessage = message(8);
        when(genericMessage.getFlags()).thenReturn(new Flags());
        when(genericMessage.getFrom()).thenReturn(new Address[]{new CustomAddress("Group <g@x>")});
        ExternalMailClient.RemoteMessageEnvelope fallback = invoke("envelope",
                new Class<?>[]{Folder.class, Message.class, ExternalMailClient.AccountConfig.class, String.class},
                generic, genericMessage,
                config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS, false, null, null, "s"),
                "INBOX");
        assertEquals("8", fallback.remoteUid());
        assertEquals("Group <g@x>", fallback.from());
        assertNotNull(fallback.internalDate());
    }

    @Test
    void envelopeRequiresPop3Uidl() throws Exception {
        Message message = message(1);
        Folder generic = mock(Folder.class);
        var wrongFolder = assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> envelope(generic, message, pop3Config("s", false, false)));
        assertEquals(ExternalMailClient.ExternalMailClientException.Category.PROTOCOL, wrongFolder.category());

        POP3Folder folder = mock(POP3Folder.class);
        when(folder.getUID(message)).thenReturn(" ");
        assertThrows(ExternalMailClient.ExternalMailClientException.class,
                () -> envelope(folder, message, pop3Config("s", false, false)));

        when(folder.getUID(message)).thenReturn("pop3-uid");
        when(message.getFlags()).thenReturn(new Flags());
        var env = envelope(folder, message, pop3Config("s", false, false));
        assertEquals("pop3-uid", env.remoteUid());
        assertNull(env.UIDValidity());
    }

    @Test
    void openRawWritesMimeToDeleteOnCloseStreamAndRejectsOtherMessages() throws Exception {
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream("Subject: raw\r\n\r\nbody".getBytes(StandardCharsets.UTF_8)));

        try (InputStream in = invoke("openRaw", new Class<?>[]{Message.class}, mime)) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("Subject: raw"));
        }

        Message other = mock(Message.class);
        assertThrows(IOException.class, () -> invoke("openRaw", new Class<?>[]{Message.class}, other));

        MimeMessage broken = mock(MimeMessage.class);
        doThrow(new MessagingException("write failed")).when(broken).writeTo(any(OutputStream.class));
        IOException error = assertThrows(IOException.class,
                () -> invoke("openRaw", new Class<?>[]{Message.class}, broken));
        assertTrue(error.getCause() instanceof MessagingException);
    }

    @Test
    void folderMetadataHelpersCoverBrokenAndGenericFolders() throws Exception {
        Folder zeroSeparator = mock(Folder.class);
        when(zeroSeparator.getSeparator()).thenReturn((char) 0);
        assertEquals("/", invokeStatic("delimiter", new Class<?>[]{Folder.class}, zeroSeparator));

        Folder brokenSeparator = mock(Folder.class);
        when(brokenSeparator.getSeparator()).thenThrow(new MessagingException("no separator"));
        assertEquals("/", invokeStatic("delimiter", new Class<?>[]{Folder.class}, brokenSeparator));

        Folder generic = mock(Folder.class);
        assertEquals(Set.of(), invokeStatic("attributes", new Class<?>[]{Folder.class}, generic));

        IMAPFolder imap = mock(IMAPFolder.class);
        when(imap.getAttributes()).thenReturn(null);
        assertEquals(Set.of(), invokeStatic("attributes", new Class<?>[]{Folder.class}, imap));
        when(imap.getAttributes()).thenReturn(new String[]{"\\Sent", "\\HasNoChildren"});
        assertEquals(Set.of("\\sent", "\\hasnochildren"),
                invokeStatic("attributes", new Class<?>[]{Folder.class}, imap));
        when(imap.getAttributes()).thenThrow(new MessagingException("broken"));
        assertEquals(Set.of(), invokeStatic("attributes", new Class<?>[]{Folder.class}, imap));

        Folder closed = mock(Folder.class);
        when(closed.isOpen()).thenReturn(false);
        invokeStatic("closeFolder", new Class<?>[]{Folder.class, boolean.class}, closed, true);
        verify(closed, org.mockito.Mockito.never()).close(true);

        Folder open = mock(Folder.class);
        when(open.isOpen()).thenReturn(true);
        doThrow(new MessagingException("close failed")).when(open).close(false);
        invokeStatic("closeFolder", new Class<?>[]{Folder.class, boolean.class}, open, false);
    }

    @Test
    void specialUseAndHeaderAddressHelpersCoverNamesAndMissingValues() throws Exception {
        assertEquals("INBOX", JakartaExternalMailClient.specialUse("Anything", "/", Set.of("\\inbox")));
        assertEquals("SENT", JakartaExternalMailClient.specialUse("X/Sent Items", "/", Set.of()));
        assertEquals("DRAFTS", JakartaExternalMailClient.specialUse("X.Черновики", ".", Set.of()));
        assertEquals("TRASH", JakartaExternalMailClient.specialUse("Deleted Messages", "/", Set.of()));
        assertEquals("SPAM", JakartaExternalMailClient.specialUse("Bulk Mail", "/", Set.of()));
        assertEquals("ARCHIVE", JakartaExternalMailClient.specialUse("All Mail", "/", Set.of()));
        assertNull(JakartaExternalMailClient.specialUse("", "/", Set.of()));
        assertNull(JakartaExternalMailClient.specialUse(null, null, Set.of()));

        Message message = message(1);
        when(message.getHeader("Message-ID")).thenReturn(null);
        assertNull(invokeStatic("firstHeader", new Class<?>[]{Message.class, String.class}, message, "Message-ID"));
        when(message.getHeader("Message-ID")).thenReturn(new String[0]);
        assertNull(invokeStatic("firstHeader", new Class<?>[]{Message.class, String.class}, message, "Message-ID"));
        assertNull(invokeStatic("firstAddress", new Class<?>[]{Address[].class}, (Object) null));
        assertNull(invokeStatic("firstAddress", new Class<?>[]{Address[].class}, (Object) new Address[0]));
    }

    @Test
    void errorMappingCoversAuthTlsNetworkProtocolAndSecretMasking() throws Exception {
        var config = config(ExternalMailAccount.Protocol.IMAP, ExternalMailAccount.Security.SSL_TLS,
                false, null, null, "super-secret");
        assertCategory(config, new AuthenticationFailedException("bad super-secret"),
                ExternalMailClient.ExternalMailClientException.Category.AUTH, false);
        assertCategory(config, new MessagingException("x", new SSLHandshakeException("certificate super-secret")),
                ExternalMailClient.ExternalMailClientException.Category.TLS, false);
        assertCategory(config, new MessagingException("x", new UnknownHostException("host")),
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, true);
        assertCategory(config, new MessagingException("x", new ConnectException("refused")),
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, true);
        assertCategory(config, new MessagingException("x", new SocketTimeoutException("slow")),
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, true);
        assertCategory(config, new FolderClosedException(mock(Folder.class), "Lost folder connection to server"),
                ExternalMailClient.ExternalMailClientException.Category.NETWORK, true);
        var protocol = assertCategory(config, new IllegalStateException("weird super-secret\r\nnext"),
                ExternalMailClient.ExternalMailClientException.Category.PROTOCOL, true);
        assertFalse(protocol.getMessage().contains("super-secret"));
        assertFalse(protocol.getMessage().contains("\n"));

        MessagingException withNext = new MessagingException("outer");
        withNext.setNextException(new UnknownHostException("dns"));
        Throwable root = invokeStatic("rootCause", new Class<?>[]{Throwable.class}, withNext);
        assertTrue(root instanceof UnknownHostException);

        String safe = invokeStatic("safeMessage",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, Throwable.class}, null, null);
        assertEquals("неизвестная ошибка", safe);
        String longSafe = invokeStatic("safeMessage",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, Throwable.class},
                config, new IllegalStateException("x".repeat(600)));
        assertEquals(500, longSafe.length());
    }

    private ExternalMailClient.ExternalMailClientException assertCategory(
            ExternalMailClient.AccountConfig config, Exception error,
            ExternalMailClient.ExternalMailClientException.Category category, boolean temporary) throws Exception {
        ExternalMailClient.ExternalMailClientException mapped = invoke("map",
                new Class<?>[]{ExternalMailClient.AccountConfig.class, Exception.class}, config, error);
        assertEquals(category, mapped.category());
        assertEquals(temporary, mapped.temporary());
        return mapped;
    }

    private ExternalMailClient.RemoteMessageEnvelope envelope(
            Folder folder, Message message, ExternalMailClient.AccountConfig config) throws Exception {
        return invoke("envelope",
                new Class<?>[]{Folder.class, Message.class, ExternalMailClient.AccountConfig.class, String.class},
                folder, message, config, "INBOX");
    }

    private Folder genericFolder(String name, int type, char separator, int count) throws Exception {
        Folder folder = mock(Folder.class);
        when(folder.getType()).thenReturn(type);
        when(folder.getSeparator()).thenReturn(separator);
        when(folder.getFullName()).thenReturn(name);
        when(folder.getMessageCount()).thenReturn(count);
        when(folder.isOpen()).thenReturn(false);
        return folder;
    }

    private IMAPFolder imapFolder(String name, char separator, String[] attributes,
                                  int count, long validity, long uidNext) throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        when(folder.getType()).thenReturn(Folder.HOLDS_MESSAGES);
        when(folder.getSeparator()).thenReturn(separator);
        when(folder.getAttributes()).thenReturn(attributes);
        when(folder.getFullName()).thenReturn(name);
        when(folder.getMessageCount()).thenReturn(count);
        when(folder.getUIDValidity()).thenReturn(validity);
        when(folder.getUIDNext()).thenReturn(uidNext);
        when(folder.isOpen()).thenReturn(false);
        return folder;
    }

    private Message message(int number) {
        Message message = mock(Message.class);
        when(message.getMessageNumber()).thenReturn(number);
        return message;
    }

    private ExternalMailClient.AccountConfig withSmtpSecurity(
            ExternalMailClient.AccountConfig base, ExternalMailAccount.Security security) {
        return new ExternalMailClient.AccountConfig(
                base.accountId(), base.ownerId(), base.emailAddress(), base.displayName(), base.provider(),
                base.incomingProtocol(), base.incomingHost(), base.incomingPort(), base.incomingSecurity(),
                base.incomingUsername(), base.secret(), true, "smtp.test", 587, security,
                base.smtpUsername(), false, false);
    }

    private ExternalMailClient.AccountConfig pop3Config(String secret, boolean markSeen, boolean deleteAfterFetch) {
        return new ExternalMailClient.AccountConfig(
                UUID.randomUUID(), UUID.randomUUID(), "user@mail.test", "User", ExternalMailAccount.Provider.CUSTOM,
                ExternalMailAccount.Protocol.POP3, "pop.test", 110, ExternalMailAccount.Security.PLAIN,
                "user", secret, false, null, null, ExternalMailAccount.Security.PLAIN,
                "user", markSeen, deleteAfterFetch);
    }

    private ExternalMailClient.AccountConfig config(
            ExternalMailAccount.Protocol protocol, ExternalMailAccount.Security security,
            boolean smtpEnabled, String smtpHost, Integer smtpPort, String secret) {
        return new ExternalMailClient.AccountConfig(
                UUID.randomUUID(), UUID.randomUUID(), "user@mail.test", "User", ExternalMailAccount.Provider.CUSTOM,
                protocol, protocol == ExternalMailAccount.Protocol.POP3 ? "pop.test" : "imap.test",
                protocol == ExternalMailAccount.Protocol.POP3 ? 110 : 993, security,
                "user", secret, smtpEnabled, smtpHost, smtpPort, ExternalMailAccount.Security.STARTTLS,
                "user", false, false);
    }

    private static ExternalMailEndpointPolicy.Endpoint endpoint(
            String host, int port, ExternalMailAccount.Security security) {
        return new ExternalMailEndpointPolicy.Endpoint(host, port, security, List.of(host));
    }

    private static String rawMessage(String id, String subject) {
        return "From: Sender <sender@partner.test>\r\n"
                + "To: user@mail.test\r\n"
                + "Subject: " + subject + "\r\n"
                + "Date: Mon, 12 Jan 2026 10:30:00 +0000\r\n"
                + "Message-ID: <" + id + "@mail.test>\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Body " + subject + "\r\n";
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = JakartaExternalMailClient.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return (T) method.invoke(client, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = JakartaExternalMailClient.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return (T) method.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    private static final class CustomAddress extends Address {
        private final String value;

        private CustomAddress(String value) {
            this.value = value;
        }

        @Override
        public String getType() {
            return "custom";
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CustomAddress custom && value.equals(custom.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    private static final class FakePop3Server implements AutoCloseable {
        private final String expectedUser;
        private final String expectedPassword;
        private final List<String> messages;
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private ServerSocket socket;
        private Thread thread;
        private volatile boolean closed;

        private FakePop3Server(String expectedUser, String expectedPassword, List<String> messages) {
            this.expectedUser = expectedUser;
            this.expectedPassword = expectedPassword;
            this.messages = messages;
        }

        void start() throws IOException {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            thread = new Thread(this::acceptLoop, "fake-pop3");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed) {
                try (Socket connection = socket.accept()) {
                    handle(connection);
                } catch (IOException e) {
                    if (closed) return;
                }
            }
        }

        private void handle(Socket connection) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = connection.getOutputStream();
            write(out, "+OK Fake POP3 ready");
            String user = null;
            Set<Integer> deleted = new java.util.HashSet<>();
            String line;
            while ((line = in.readLine()) != null) {
                commands.add(line);
                String upper = line.toUpperCase();
                if (upper.startsWith("USER ")) {
                    user = line.substring(5).trim();
                    write(out, "+OK user accepted");
                } else if (upper.startsWith("PASS ")) {
                    String password = line.substring(5).trim();
                    write(out, expectedUser.equals(user) && expectedPassword.equals(password)
                            ? "+OK mailbox locked" : "-ERR authentication failed");
                } else if (upper.equals("CAPA")) {
                    write(out, "+OK Capability list follows");
                    write(out, "UIDL");
                    write(out, "TOP");
                    write(out, "USER");
                    write(out, ".");
                } else if (upper.equals("STAT")) {
                    write(out, "+OK " + messages.size() + " " + totalSize());
                } else if (upper.equals("LIST")) {
                    write(out, "+OK " + messages.size() + " messages");
                    for (int i = 0; i < messages.size(); i++) write(out, (i + 1) + " " + size(i));
                    write(out, ".");
                } else if (upper.startsWith("LIST ")) {
                    int n = number(line);
                    write(out, "+OK " + n + " " + size(n - 1));
                } else if (upper.equals("UIDL")) {
                    write(out, "+OK unique-id listing follows");
                    for (int i = 0; i < messages.size(); i++) write(out, (i + 1) + " uid-" + word(i));
                    write(out, ".");
                } else if (upper.startsWith("UIDL ")) {
                    int n = number(line);
                    write(out, "+OK " + n + " uid-" + word(n - 1));
                } else if (upper.startsWith("RETR ")) {
                    int n = number(line);
                    write(out, "+OK " + size(n - 1) + " octets");
                    writeMessage(out, messages.get(n - 1), false);
                    write(out, ".");
                } else if (upper.startsWith("TOP ")) {
                    int n = number(line);
                    write(out, "+OK top follows");
                    writeMessage(out, messages.get(n - 1), true);
                    write(out, ".");
                } else if (upper.startsWith("DELE ")) {
                    deleted.add(number(line));
                    write(out, "+OK deleted");
                } else if (upper.equals("RSET")) {
                    deleted.clear();
                    write(out, "+OK reset");
                } else if (upper.equals("NOOP")) {
                    write(out, "+OK noop");
                } else if (upper.equals("QUIT")) {
                    write(out, "+OK bye");
                    return;
                } else {
                    write(out, "-ERR unknown command");
                }
            }
        }

        private int number(String line) {
            String[] parts = line.trim().split("\\s+");
            return Integer.parseInt(parts[1]);
        }

        private String word(int index) {
            return index == 0 ? "one" : "two";
        }

        private int size(int index) {
            return messages.get(index).getBytes(StandardCharsets.UTF_8).length;
        }

        private int totalSize() {
            int sum = 0;
            for (int i = 0; i < messages.size(); i++) sum += size(i);
            return sum;
        }

        private void writeMessage(OutputStream out, String message, boolean headersOnly) throws IOException {
            String[] lines = message.split("\r\n", -1);
            for (String current : lines) {
                if (headersOnly && current.isEmpty()) {
                    write(out, "");
                    return;
                }
                write(out, current.startsWith(".") ? "." + current : current);
            }
        }

        private void write(OutputStream out, String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (socket != null) socket.close();
        }
    }

    private static final class FakeSmtpServer implements AutoCloseable {
        private final String expectedUser;
        private final String expectedPassword;
        private final List<String> commands = new CopyOnWriteArrayList<>();
        private ServerSocket socket;
        private Thread thread;
        private volatile boolean closed;

        private FakeSmtpServer(String expectedUser, String expectedPassword) {
            this.expectedUser = expectedUser;
            this.expectedPassword = expectedPassword;
        }

        void start() throws IOException {
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            thread = new Thread(this::acceptLoop, "fake-smtp");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed) {
                try (Socket connection = socket.accept()) {
                    handle(connection);
                } catch (IOException e) {
                    if (closed) return;
                }
            }
        }

        private void handle(Socket connection) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = connection.getOutputStream();
            write(out, "220 localhost Fake SMTP ready");
            String line;
            while ((line = in.readLine()) != null) {
                commands.add(line);
                String upper = line.toUpperCase();
                if (upper.startsWith("EHLO ")) {
                    write(out, "250-localhost");
                    write(out, "250-AUTH LOGIN PLAIN");
                    write(out, "250 OK");
                } else if (upper.startsWith("HELO ")) {
                    write(out, "250 localhost");
                } else if (upper.startsWith("AUTH PLAIN")) {
                    String encoded;
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        encoded = parts[2];
                    } else {
                        write(out, "334 ");
                        encoded = in.readLine();
                    }
                    String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                    String[] credentials = decoded.split("\0");
                    boolean ok = credentials.length >= 3
                            && expectedUser.equals(credentials[1])
                            && expectedPassword.equals(credentials[2]);
                    write(out, ok ? "235 Authentication successful" : "535 Authentication failed");
                } else if (upper.startsWith("AUTH LOGIN")) {
                    String[] parts = line.split("\\s+");
                    String userEncoded;
                    if (parts.length >= 3) {
                        userEncoded = parts[2];
                    } else {
                        write(out, "334 VXNlcm5hbWU6");
                        userEncoded = in.readLine();
                    }
                    write(out, "334 UGFzc3dvcmQ6");
                    String passEncoded = in.readLine();
                    String user = new String(Base64.getDecoder().decode(userEncoded), StandardCharsets.UTF_8);
                    String password = new String(Base64.getDecoder().decode(passEncoded), StandardCharsets.UTF_8);
                    write(out, expectedUser.equals(user) && expectedPassword.equals(password)
                            ? "235 Authentication successful" : "535 Authentication failed");
                } else if (upper.equals("QUIT")) {
                    write(out, "221 Bye");
                    return;
                } else if (upper.equals("RSET") || upper.equals("NOOP")) {
                    write(out, "250 OK");
                } else {
                    write(out, "502 Command not implemented");
                }
            }
        }

        private void write(OutputStream out, String line) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (socket != null) socket.close();
        }
    }
}
