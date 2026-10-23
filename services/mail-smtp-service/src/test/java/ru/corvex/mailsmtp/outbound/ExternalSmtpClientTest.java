package ru.corvex.mailsmtp.outbound;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory;
import org.subethamail.smtp.auth.LoginFailedException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalSmtpClientTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OWNER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final byte[] EML = ("From: old@example.ru\r\n"
            + "To: good@example.org\r\nSubject: Provider send\r\n\r\nHello provider\r\n")
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void blocksLoopbackProviderEndpointBeforeNetworkCall() {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");
        var config = config("127.0.0.1", 993, "SSL_TLS", "127.0.0.1", 465, "SSL_TLS");

        var results = client.send(config, EML, "old@example.ru", List.of("client@example.org"));

        assertEquals(1, results.size());
        assertEquals(SmtpRelayClient.Status.PERMANENT, results.get(0).status());
    }

    @Test
    void invalidEndpointsArePermanentBeforeNetworkCall() {
        ExternalSmtpClient strict = new ExternalSmtpClient(false, false, "mail.company.local");
        List<ExternalAccountConfigClient.TransportConfig> invalid = List.of(
                config("imap.example.ru", 993, "SSL_TLS", null, 465, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", " ", 465, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", "93.184.216.34", null, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", "93.184.216.34", 0, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", "93.184.216.34", 65_536, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", "93.184.216.34", 587, "PLAIN"),
                config("imap.example.ru", 993, "SSL_TLS", "0.0.0.0", 465, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", "169.254.10.20", 465, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", "224.0.0.1", 465, "SSL_TLS"),
                config("imap.example.ru", 993, "SSL_TLS", "192.168.10.20", 465, "SSL_TLS"));

        for (var cfg : invalid) {
            var results = strict.send(cfg, EML, "old@example.ru", List.of("client@example.org"));
            assertEquals(SmtpRelayClient.Status.PERMANENT, results.get(0).status(), String.valueOf(cfg.smtpHost()));
        }
    }

    @Test
    void unresolvableProviderHostIsPermanentConfigurationFailure() {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");
        var cfg = config("imap.example.ru", 993, "SSL_TLS", "nonexistent.invalid", 465, "SSL_TLS");

        var result = client.send(cfg, EML, "old@example.ru", List.of("client@example.org"));

        assertEquals(SmtpRelayClient.Status.PERMANENT, result.get(0).status());
        assertTrue(result.get(0).diagnostic().contains("DNS"));
    }

    @Test
    void endpointValidatorHonorsPrivateNetworkAndPlainOptIns() throws Exception {
        ExternalSmtpClient permissive = new ExternalSmtpClient(true, true, "mail.company.local");

        assertDoesNotThrow(() -> validate(permissive, "10.20.30.40", 587, "PLAIN"));
        assertDoesNotThrow(() -> validate(permissive, "93.184.216.34", 465, "SSL_TLS"));

        IllegalArgumentException loopback = validateFailure(permissive, "::1", 465, "SSL_TLS");
        assertTrue(loopback.getMessage().contains("запрещённый"));
    }

    @Test
    void privateAndReservedAddressDetectionCoversIpv4AndIpv6Ranges() throws Exception {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");

        assertTrue(privateOrReserved(client, "192.168.1.10"));
        assertTrue(privateOrReserved(client, "0.1.2.3"));
        assertFalse(privateOrReserved(client, "100.63.255.255"));
        assertTrue(privateOrReserved(client, "100.64.0.1"));
        assertTrue(privateOrReserved(client, "100.127.255.255"));
        assertFalse(privateOrReserved(client, "100.128.0.1"));
        assertTrue(privateOrReserved(client, "240.0.0.1"));
        assertFalse(privateOrReserved(client, "93.184.216.34"));
        assertTrue(privateOrReserved(client, "fd12:3456::1"));
        assertFalse(privateOrReserved(client, "2001:db8::1"));
        assertFalse(privateOrReserved(client, "fe80::1"));
    }

    @Test
    void buildsSmtpAndIncomingPropertiesForSecurityModes() throws Exception {
        ExternalSmtpClient client = new ExternalSmtpClient(true, true, "smtp.company.local");

        Properties ssl = smtpProperties(client, config("imap.example.ru", 993, "STARTTLS", "smtp.example.ru", 465, "SSL_TLS"));
        assertEquals("smtp", ssl.getProperty("mail.transport.protocol"));
        assertEquals("smtp.example.ru", ssl.getProperty("mail.smtp.host"));
        assertEquals("465", ssl.getProperty("mail.smtp.port"));
        assertEquals("true", ssl.getProperty("mail.smtp.auth"));
        assertEquals("old@example.ru", ssl.getProperty("mail.smtp.from"));
        assertEquals("smtp.company.local", ssl.getProperty("mail.smtp.localhost"));
        assertEquals("true", ssl.getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", ssl.getProperty("mail.smtp.ssl.checkserveridentity"));

        Properties starttls = smtpProperties(client, config("imap.example.ru", 143, "PLAIN", "smtp.example.ru", 587, "STARTTLS"));
        assertEquals("true", starttls.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", starttls.getProperty("mail.smtp.starttls.required"));
        assertNull(starttls.getProperty("mail.smtp.ssl.enable"));

        Properties plain = smtpProperties(client, config("imap.example.ru", 143, "PLAIN", "smtp.example.ru", 25, "PLAIN"));
        assertNull(plain.getProperty("mail.smtp.ssl.enable"));
        assertNull(plain.getProperty("mail.smtp.starttls.enable"));

        Properties incomingTls = incomingProperties(client, config("imap.example.ru", 993, "SSL_TLS", "smtp.example.ru", 465, "SSL_TLS"));
        assertEquals("imaps", incomingTls.getProperty("mail.store.protocol"));
        assertEquals("imap.example.ru", incomingTls.getProperty("mail.imap.host"));
        assertEquals("993", incomingTls.getProperty("mail.imap.port"));
        assertEquals("true", incomingTls.getProperty("mail.imap.ssl.checkserveridentity"));

        Properties incomingStarttls = incomingProperties(client, config("imap.example.ru", 143, "STARTTLS", "smtp.example.ru", 587, "STARTTLS"));
        assertEquals("imap", incomingStarttls.getProperty("mail.store.protocol"));
        assertEquals("true", incomingStarttls.getProperty("mail.imap.starttls.enable"));
        assertEquals("true", incomingStarttls.getProperty("mail.imap.starttls.required"));
        assertEquals("imaps", invokePrivate(client, "storeProtocol", new Class<?>[]{String.class}, "ssl_tls"));
        assertEquals("imap", invokePrivate(client, "storeProtocol", new Class<?>[]{String.class}, "PLAIN"));
    }

    @Test
    void appendSentShortCircuitsWhenDisabledNotImapOrEndpointForbidden() {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");

        assertFalse(client.appendSent(config("127.0.0.1", 993, "SSL_TLS", "93.184.216.34", 465, "SSL_TLS",
                false, "IMAP"), EML));
        assertFalse(client.appendSent(config("127.0.0.1", 995, "SSL_TLS", "93.184.216.34", 465, "SSL_TLS",
                true, "POP3"), EML));
        assertFalse(client.appendSent(config("127.0.0.1", 993, "SSL_TLS", "93.184.216.34", 465, "SSL_TLS",
                true, "imap"), EML));
    }

    @Test
    void appendSentReturnsFalseWhenProviderStoreIsUnreachable() throws IOException {
        String host = siteLocalHost();
        int closedPort = closedPort();
        ExternalSmtpClient client = new ExternalSmtpClient(true, true, "mail.company.local");

        assertFalse(client.appendSent(config(host, closedPort, "PLAIN", host, 465, "SSL_TLS", true, "IMAP"), EML));
        assertFalse(client.appendSent(config(host, closedPort, "STARTTLS", host, 465, "SSL_TLS", true, "IMAP"), EML));
        assertFalse(client.appendSent(config(host, closedPort, "SSL_TLS", host, 465, "SSL_TLS", true, "IMAP"), EML));
    }

    @Test
    void authenticatedProviderSendSucceedsAndUsesEffectiveUsername() throws Exception {
        String host = siteLocalHost();
        List<String> logins = new CopyOnWriteArrayList<>();
        EasyAuthenticationHandlerFactory auth = new EasyAuthenticationHandlerFactory((username, password, context) -> {
            logins.add(username + ":" + password);
            if (!"incoming-user".equals(username) || !"app-password".equals(password)) {
                throw new LoginFailedException("bad credentials");
            }
        });
        try (FakeSmtpServer server = new FakeSmtpServer(0, InetAddress.getByName(host), auth)) {
            ExternalSmtpClient client = new ExternalSmtpClient(true, true, "mail.company.local");
            var cfg = new ExternalAccountConfigClient.TransportConfig(
                    ACCOUNT_ID, OWNER_ID, "old@example.ru", null, "CUSTOM", "IMAP", host, 993, "SSL_TLS",
                    "incoming-user", "app-password", host, server.port(), "PLAIN", " ", true, "Sent");

            var results = client.send(cfg, EML, "old@example.ru", List.of("good@example.org"));

            assertEquals(SmtpRelayClient.Status.SENT, results.get(0).status());
            assertEquals(host, results.get(0).remoteHost());
            assertEquals(List.of("incoming-user:app-password"), logins);
            assertEquals(List.of("good@example.org"), server.recipients());
            assertTrue(server.messageTexts().get(0).contains("Hello provider"));
        }
    }

    @Test
    void providerAuthenticationFailureAndMixedRecipientsAreClassified() throws Exception {
        String host = siteLocalHost();
        EasyAuthenticationHandlerFactory auth = new EasyAuthenticationHandlerFactory((username, password, context) -> {
            if (!"secret".equals(password)) {
                throw new LoginFailedException("bad password");
            }
        });
        try (FakeSmtpServer server = new FakeSmtpServer(0, InetAddress.getByName(host), auth)) {
            ExternalSmtpClient client = new ExternalSmtpClient(true, true, "mail.company.local");
            var cfg = config(host, 993, "SSL_TLS", host, server.port(), "PLAIN", "secret", true, "IMAP");

            var mixed = client.send(cfg, EML, "old@example.ru",
                    List.of("good@example.org", "bad@example.org", "temp@example.org"));
            Map<String, SmtpRelayClient.RecipientResult> byRecipient = byRecipient(mixed);
            assertEquals(SmtpRelayClient.Status.TEMPORARY, byRecipient.get("good@example.org").status());
            assertEquals(SmtpRelayClient.Status.PERMANENT, byRecipient.get("bad@example.org").status());
            assertEquals(SmtpRelayClient.Status.TEMPORARY, byRecipient.get("temp@example.org").status());

            var wrongPassword = config(host, 993, "SSL_TLS", host, server.port(), "PLAIN", "wrong", true, "IMAP");
            var authFailure = client.send(wrongPassword, EML, "old@example.ru", List.of("good@example.org"));
            assertEquals(SmtpRelayClient.Status.PERMANENT, authFailure.get(0).status());
            assertTrue(authFailure.get(0).diagnostic().contains("пароль приложения"));
        }
    }

    @Test
    void providerStarttlsRequirementAndInvalidRecipientSyntaxAreTemporary() throws Exception {
        String host = siteLocalHost();
        EasyAuthenticationHandlerFactory auth = new EasyAuthenticationHandlerFactory((username, password, context) -> {
        });
        try (FakeSmtpServer server = new FakeSmtpServer(0, InetAddress.getByName(host), auth)) {
            ExternalSmtpClient client = new ExternalSmtpClient(true, true, "mail.company.local");

            var starttls = config(host, 993, "SSL_TLS", host, server.port(), "STARTTLS", "secret", true, "IMAP");
            assertEquals(SmtpRelayClient.Status.TEMPORARY,
                    client.send(starttls, EML, "old@example.ru", List.of("good@example.org")).get(0).status());

            var plain = config(host, 993, "SSL_TLS", host, server.port(), "PLAIN", "secret", true, "IMAP");
            assertEquals(SmtpRelayClient.Status.TEMPORARY,
                    client.send(plain, EML, "old@example.ru", List.of("not an address")).get(0).status());
        }
    }

    @Test
    void classifySendFailedHandlesOtherAddressTypesAndMissingRecipients() throws Exception {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");
        List<String> recipients = List.of("sent@example.org", "invalid@example.org", "other@example.org", "missing@example.org");
        SendFailedException error = new SendFailedException(
                "partial", new Exception("gateway"),
                new Address[]{new InternetAddress("sent@example.org")},
                new Address[]{new OtherAddress("other@example.org")},
                new Address[]{new InternetAddress("invalid@example.org")});

        Map<String, SmtpRelayClient.RecipientResult> results = byRecipient(classifySendFailed(client, error, recipients));

        assertEquals(SmtpRelayClient.Status.SENT, results.get("sent@example.org").status());
        assertEquals(SmtpRelayClient.Status.PERMANENT, results.get("invalid@example.org").status());
        assertEquals(SmtpRelayClient.Status.TEMPORARY, results.get("other@example.org").status());
        assertEquals(SmtpRelayClient.Status.TEMPORARY, results.get("missing@example.org").status());
    }

    @Test
    void exceptionClassificationAndRootMessageNormalization() throws Exception {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");

        assertEquals(SmtpRelayClient.Status.PERMANENT,
                classify(client, new MessagingException("send", new SSLException("bad certificate"))));
        assertEquals(SmtpRelayClient.Status.PERMANENT,
                classify(client, new AuthenticationFailedException("535 authentication failed")));
        assertEquals(SmtpRelayClient.Status.TEMPORARY,
                classify(client, new MessagingException("send", new UnknownHostException("smtp.example"))));
        assertEquals(SmtpRelayClient.Status.TEMPORARY,
                classify(client, new MessagingException("send", new ConnectException("refused"))));
        assertEquals(SmtpRelayClient.Status.TEMPORARY,
                classify(client, new MessagingException("send", new SocketTimeoutException("timeout"))));
        assertEquals(SmtpRelayClient.Status.PERMANENT, classify(client, new MessagingException("550 rejected")));
        assertEquals(SmtpRelayClient.Status.TEMPORARY, classify(client, new MessagingException("450 busy")));

        String longMessage = "first\r\n" + "x".repeat(600) + "\nlast";
        String root = rootMessage(client, new MessagingException("outer", new IllegalStateException(longMessage)));
        assertEquals(500, root.length());
        assertFalse(root.contains("\r"));
        assertFalse(root.contains("\n"));
        assertEquals("IllegalStateException", rootMessage(client, new IllegalStateException()));
    }

    @Test
    void findSentFolderPrefersConfiguredFolderThenSpecialUseThenName() throws Exception {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");

        Store preferredStore = mock(Store.class);
        Folder preferred = mock(Folder.class);
        when(preferred.exists()).thenReturn(true);
        when(preferredStore.getFolder("Provider Sent")).thenReturn(preferred);
        assertSame(preferred, findSent(client, preferredStore, "Provider Sent"));

        Store specialStore = mock(Store.class);
        Folder missingPreferred = mock(Folder.class);
        when(missingPreferred.exists()).thenReturn(false);
        when(specialStore.getFolder("Missing")).thenReturn(missingPreferred);
        Folder root = mock(Folder.class);
        when(specialStore.getDefaultFolder()).thenReturn(root);

        Folder skipped = mock(Folder.class);
        when(skipped.getType()).thenReturn(0);
        when(skipped.getFullName()).thenReturn("Sent");

        IMAPFolder brokenAttributes = mock(IMAPFolder.class);
        when(brokenAttributes.getType()).thenReturn(Folder.HOLDS_MESSAGES);
        when(brokenAttributes.getAttributes()).thenThrow(new MessagingException("attributes unavailable"));
        when(brokenAttributes.getFullName()).thenReturn("INBOX.Sent Items");

        IMAPFolder specialUse = mock(IMAPFolder.class);
        when(specialUse.getType()).thenReturn(Folder.HOLDS_MESSAGES);
        when(specialUse.getAttributes()).thenReturn(new String[]{"\\HasNoChildren", "\\Sent"});
        when(specialUse.getFullName()).thenReturn("Archive/ProviderCopy");

        when(root.list("*")).thenReturn(new Folder[]{skipped, brokenAttributes, specialUse});
        assertSame(specialUse, findSent(client, specialStore, "Missing"));
    }

    @Test
    void findSentFolderUsesLocalizedNameAndReturnsNullWhenAbsent() throws Exception {
        ExternalSmtpClient client = new ExternalSmtpClient(false, false, "mail.company.local");
        Store store = mock(Store.class);
        Folder root = mock(Folder.class);
        when(store.getDefaultFolder()).thenReturn(root);

        Folder inbox = folder("INBOX");
        Folder russianSent = folder("Почта.Отправленные");
        when(root.list("*")).thenReturn(new Folder[]{inbox, russianSent});
        assertSame(russianSent, findSent(client, store, " "));

        Folder trash = folder("Trash");
        when(root.list("*")).thenReturn(new Folder[]{inbox, trash});
        assertNull(findSent(client, store, null));
    }

    private static Folder folder(String fullName) throws MessagingException {
        Folder folder = mock(Folder.class);
        when(folder.getType()).thenReturn(Folder.HOLDS_MESSAGES);
        when(folder.getFullName()).thenReturn(fullName);
        return folder;
    }

    private static ExternalAccountConfigClient.TransportConfig config(
            String incomingHost, int incomingPort, String incomingSecurity,
            String smtpHost, Integer smtpPort, String smtpSecurity) {
        return config(incomingHost, incomingPort, incomingSecurity, smtpHost, smtpPort, smtpSecurity, "secret", true, "IMAP");
    }

    private static ExternalAccountConfigClient.TransportConfig config(
            String incomingHost, int incomingPort, String incomingSecurity,
            String smtpHost, Integer smtpPort, String smtpSecurity,
            boolean saveSent, String incomingProtocol) {
        return config(incomingHost, incomingPort, incomingSecurity, smtpHost, smtpPort, smtpSecurity,
                "secret", saveSent, incomingProtocol);
    }

    private static ExternalAccountConfigClient.TransportConfig config(
            String incomingHost, int incomingPort, String incomingSecurity,
            String smtpHost, Integer smtpPort, String smtpSecurity,
            String secret, boolean saveSent, String incomingProtocol) {
        return new ExternalAccountConfigClient.TransportConfig(
                ACCOUNT_ID, OWNER_ID, "old@example.ru", null, "CUSTOM", incomingProtocol,
                incomingHost, incomingPort, incomingSecurity, "old@example.ru", secret,
                smtpHost, smtpPort, smtpSecurity, null, saveSent, "Sent");
    }

    private static Map<String, SmtpRelayClient.RecipientResult> byRecipient(List<SmtpRelayClient.RecipientResult> results) {
        return results.stream().collect(Collectors.toMap(
                SmtpRelayClient.RecipientResult::recipient, Function.identity()));
    }

    private static String siteLocalHost() throws IOException {
        for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!network.isUp() || network.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(network.getInetAddresses())) {
                if (address instanceof Inet4Address && address.isSiteLocalAddress()
                        && canConnectToOwnAddress(address)) {
                    return address.getHostAddress();
                }
            }
        }
        Assumptions.abort("No site-local IPv4 address is available for the provider SMTP test");
        throw new AssertionError("unreachable");
    }

    private static boolean canConnectToOwnAddress(InetAddress address) {
        try (ServerSocket listener = new ServerSocket(0, 1, address);
             java.net.Socket probe = new java.net.Socket()) {
            probe.connect(new java.net.InetSocketAddress(address, listener.getLocalPort()), 500);
            return probe.isConnected();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void validate(ExternalSmtpClient client, String host, Integer port, String security) throws Exception {
        invokePrivate(client, "validateEndpoint", new Class<?>[]{String.class, Integer.class, String.class}, host, port, security);
    }

    private static IllegalArgumentException validateFailure(
            ExternalSmtpClient client, String host, Integer port, String security) throws Exception {
        try {
            validate(client, host, port, security);
        } catch (IllegalArgumentException e) {
            return e;
        }
        throw new AssertionError("Expected endpoint validation to fail");
    }

    private static boolean privateOrReserved(ExternalSmtpClient client, String host) throws Exception {
        return (Boolean) invokePrivate(client, "isPrivateOrReserved",
                new Class<?>[]{InetAddress.class}, InetAddress.getByName(host));
    }

    private static Properties smtpProperties(ExternalSmtpClient client, ExternalAccountConfigClient.TransportConfig cfg) throws Exception {
        return (Properties) invokePrivate(client, "smtpProperties",
                new Class<?>[]{ExternalAccountConfigClient.TransportConfig.class}, cfg);
    }

    private static Properties incomingProperties(ExternalSmtpClient client, ExternalAccountConfigClient.TransportConfig cfg) throws Exception {
        return (Properties) invokePrivate(client, "incomingProperties",
                new Class<?>[]{ExternalAccountConfigClient.TransportConfig.class}, cfg);
    }

    private static SmtpRelayClient.Status classify(ExternalSmtpClient client, Throwable error) throws Exception {
        return (SmtpRelayClient.Status) invokePrivate(client, "classify", new Class<?>[]{Throwable.class}, error);
    }

    private static String rootMessage(ExternalSmtpClient client, Throwable error) throws Exception {
        return (String) invokePrivate(client, "rootMessage", new Class<?>[]{Throwable.class}, error);
    }

    @SuppressWarnings("unchecked")
    private static List<SmtpRelayClient.RecipientResult> classifySendFailed(
            ExternalSmtpClient client, SendFailedException error, List<String> recipients) throws Exception {
        return (List<SmtpRelayClient.RecipientResult>) invokePrivate(client, "classifySendFailed",
                new Class<?>[]{SendFailedException.class, List.class, String.class}, error, recipients, "smtp.example.ru");
    }

    private static Folder findSent(ExternalSmtpClient client, Store store, String preferred) throws Exception {
        return (Folder) invokePrivate(client, "findSentFolder", new Class<?>[]{Store.class, String.class}, store, preferred);
    }

    private static Object invokePrivate(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target instanceof Class<?> clazz
                ? clazz.getDeclaredMethod(name, types)
                : target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(target instanceof Class<?> ? null : target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static final class OtherAddress extends Address {
        private final String value;

        private OtherAddress(String value) {
            this.value = value;
        }

        @Override
        public String getType() {
            return "other";
        }

        @Override
        public String toString() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof OtherAddress that && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
