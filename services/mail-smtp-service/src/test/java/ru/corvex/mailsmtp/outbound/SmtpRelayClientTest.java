package ru.corvex.mailsmtp.outbound;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpRelayClientTest {

    private static final byte[] EML = ("From: Alice <alice@company.local>\r\n"
            + "To: good@example.test\r\n"
            + "Subject: Relay test\r\n"
            + "Date: Mon, 19 Oct 2026 10:00:00 +0000\r\n"
            + "Message-ID: <relay-1@company.local>\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n"
            + "\r\nHello relay\r\n").getBytes(StandardCharsets.UTF_8);

    private static FakeSmtpServer sharedServer;

    @AfterAll
    static void stopSharedServer() {
        if (sharedServer != null) {
            sharedServer.close();
            sharedServer = null;
        }
    }

    @Test
    void extractsPermanentSmtpCode() {
        assertEquals(550, SmtpRelayClient.smtpCode(new MessagingException("550 5.1.1 user unknown")));
    }

    @Test
    void extractsTemporarySmtpCodeFromCause() {
        MessagingException outer = new MessagingException("send failed", new Exception("451 temporary local problem"));
        assertEquals(451, SmtpRelayClient.smtpCode(outer));
    }

    @Test
    void extractsCodeFromNextExceptionAndReturnsZeroWithoutCode() {
        MessagingException outer = new MessagingException("send failed without code");
        outer.setNextException(new Exception("451 temporary local problem"));
        assertEquals(451, SmtpRelayClient.smtpCode(outer));

        assertEquals(0, SmtpRelayClient.smtpCode(new MessagingException("99550 is not a standalone code")));
        assertEquals(0, SmtpRelayClient.smtpCode(new MessagingException()));
        assertEquals(0, SmtpRelayClient.smtpCode(null));
    }

    @Test
    void sendsSuccessfullyThroughSubEthaServerOnPort25() {
        FakeSmtpServer server = relayServer();
        SmtpRelayClient client = client(List.of("127.0.0.1"), false);

        List<SmtpRelayClient.RecipientResult> results = client.sendToDomain(
                EML, "alice@company.local", "example.test", List.of("good@example.test"));

        assertEquals(1, results.size());
        assertEquals(SmtpRelayClient.Status.SENT, results.get(0).status());
        assertEquals("127.0.0.1", results.get(0).remoteHost());
        assertEquals(List.of("good@example.test"), server.recipients());
        assertEquals(1, server.messageTexts().size());
        assertTrue(server.messageTexts().get(0).contains("Hello relay"));
        assertTrue(server.senders().stream().anyMatch(from -> from.contains("alice@company.local")));
    }

    @Test
    void classifiesMixedRcptResultsWithoutSendingPartialData() {
        FakeSmtpServer server = relayServer();
        SmtpRelayClient client = client(List.of("127.0.0.1"), false);

        List<SmtpRelayClient.RecipientResult> results = client.sendToDomain(
                EML, "alice@company.local", "example.test",
                List.of("good@example.test", "bad@example.test", "temp@example.test"));

        Map<String, SmtpRelayClient.RecipientResult> byRecipient = byRecipient(results);
        assertEquals(SmtpRelayClient.Status.PERMANENT, byRecipient.get("bad@example.test").status());
        assertEquals(SmtpRelayClient.Status.TEMPORARY, byRecipient.get("good@example.test").status());
        assertEquals(SmtpRelayClient.Status.TEMPORARY, byRecipient.get("temp@example.test").status());
        assertEquals(0, server.dataAttempts(), "sendpartial=false must abort DATA when any RCPT fails");
    }

    @Test
    void permanentRcptFailureStopsTryingFurtherMxHosts() {
        FakeSmtpServer server = relayServer();
        SmtpRelayClient client = client(List.of("127.0.0.1", "127.0.0.1"), false);

        List<SmtpRelayClient.RecipientResult> results = client.sendToDomain(
                EML, "alice@company.local", "example.test", List.of("bad@example.test"));

        assertEquals(SmtpRelayClient.Status.PERMANENT, results.get(0).status());
        assertEquals(1, server.recipients().size());
    }

    @Test
    void temporaryDataFailureIsRetriedOnNextMxHost() {
        FakeSmtpServer server = relayServer();
        server.rejectFirstDataWithCode = 451;
        SmtpRelayClient client = client(List.of("127.0.0.1", "127.0.0.1"), false);

        List<SmtpRelayClient.RecipientResult> results = client.sendToDomain(
                EML, "alice@company.local", "example.test", List.of("good@example.test"));

        assertEquals(SmtpRelayClient.Status.SENT, results.get(0).status());
        assertEquals(2, server.dataAttempts());
        assertEquals(1, server.messageTexts().size());
    }

    @Test
    void invalidRecipientSyntaxIsTemporaryAndRequiredStarttlsFailsTemporarilyOnPlainServer() {
        relayServer();
        SmtpRelayClient client = client(List.of("127.0.0.1"), false);

        List<SmtpRelayClient.RecipientResult> invalid = client.sendToDomain(
                EML, "alice@company.local", "example.test", List.of("not an address"));
        assertEquals(SmtpRelayClient.Status.TEMPORARY, invalid.get(0).status());

        SmtpRelayClient tlsRequired = client(List.of("127.0.0.1"), true);
        List<SmtpRelayClient.RecipientResult> tls = tlsRequired.sendToDomain(
                EML, "alice@company.local", "example.test", List.of("good@example.test"));
        assertEquals(SmtpRelayClient.Status.TEMPORARY, tls.get(0).status());
    }

    @Test
    void closedPort25IsTemporary() throws IOException {
        stopSharedServer();
        try (ServerSocket probe = new ServerSocket(25, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(25, probe.getLocalPort());
        } catch (IOException e) {
            Assumptions.abort("Port 25 is unavailable for the closed-port check: " + e.getMessage());
        }
        SmtpRelayClient client = client(List.of("127.0.0.1"), false);

        List<SmtpRelayClient.RecipientResult> results = client.sendToDomain(
                EML, "alice@company.local", "example.test", List.of("good@example.test"));

        assertEquals(SmtpRelayClient.Status.TEMPORARY, results.get(0).status());
        assertNull(results.get(0).remoteHost());
    }

    @Test
    void noMxHostsAndNoRecipientsDoNotAttemptNetworkDelivery() {
        SmtpRelayClient noHosts = client(List.of(), false);
        List<SmtpRelayClient.RecipientResult> noHostResults = noHosts.sendToDomain(
                EML, "alice@company.local", "example.test", List.of("good@example.test"));
        assertEquals(SmtpRelayClient.Status.TEMPORARY, noHostResults.get(0).status());
        assertEquals("MX недоступен", noHostResults.get(0).diagnostic());
        assertNull(noHostResults.get(0).remoteHost());

        SmtpRelayClient noRecipients = client(List.of("127.0.0.1"), false);
        assertTrue(noRecipients.sendToDomain(EML, "alice@company.local", "example.test", List.of()).isEmpty());
    }

    @Test
    void classifySendFailedUsesCodeRulesAndFillsMissingRecipients() throws Exception {
        SmtpRelayClient client = client(List.of(), false);
        List<String> recipients = List.of(
                "sent@example.test", "invalid@example.test", "unsent@example.test", "missing@example.test");

        SendFailedException noCode = new SendFailedException(
                "partial failure", new Exception("gateway failed"),
                addresses("sent@example.test"), addresses("unsent@example.test"), addresses("invalid@example.test"));
        Map<String, SmtpRelayClient.RecipientResult> noCodeResults = byRecipient(classify(client, noCode, recipients));
        assertEquals(SmtpRelayClient.Status.SENT, noCodeResults.get("sent@example.test").status());
        assertEquals(SmtpRelayClient.Status.PERMANENT, noCodeResults.get("invalid@example.test").status());
        assertEquals(SmtpRelayClient.Status.TEMPORARY, noCodeResults.get("unsent@example.test").status());
        assertEquals(SmtpRelayClient.Status.TEMPORARY, noCodeResults.get("missing@example.test").status());

        SendFailedException temporaryCode = new SendFailedException(
                "451 temporary", null, null, null, addresses("invalid@example.test"));
        assertEquals(SmtpRelayClient.Status.TEMPORARY,
                byRecipient(classify(client, temporaryCode, recipients)).get("invalid@example.test").status());

        SendFailedException permanentCode = new SendFailedException(
                "550 permanent", null, null, null, addresses("invalid@example.test"));
        assertEquals(SmtpRelayClient.Status.PERMANENT,
                byRecipient(classify(client, permanentCode, recipients)).get("invalid@example.test").status());
    }

    @Test
    void rootMessageFallsBackToExceptionClassName() throws Exception {
        SmtpRelayClient client = client(List.of(), false);
        Method rootMessage = SmtpRelayClient.class.getDeclaredMethod("rootMessage", Throwable.class);
        rootMessage.setAccessible(true);

        assertEquals("MessagingException", rootMessage.invoke(client, new MessagingException()));
        assertEquals("root cause", rootMessage.invoke(client,
                new MessagingException("outer", new Exception("root cause"))));
    }

    private static SmtpRelayClient client(List<String> hosts, boolean requireStarttls) {
        MxResolver resolver = new MxResolver() {
            @Override
            public List<String> lookupMxHosts(String domain) {
                assertEquals("example.test", domain);
                return hosts;
            }
        };
        return new SmtpRelayClient(resolver, "mail.company.local", requireStarttls);
    }

    private static FakeSmtpServer relayServer() {
        if (sharedServer == null) {
            try {
                sharedServer = new FakeSmtpServer(25, InetAddress.getByName("127.0.0.1"), null);
            } catch (Exception e) {
                Assumptions.abort("Cannot bind test SMTP server to 127.0.0.1:25: " + e.getMessage());
                throw new AssertionError(e);
            }
        }
        sharedServer.reset();
        return sharedServer;
    }

    private static Address[] addresses(String... emails) throws Exception {
        Address[] addresses = new Address[emails.length];
        for (int i = 0; i < emails.length; i++) {
            addresses[i] = new InternetAddress(emails[i]);
        }
        return addresses;
    }

    private static Map<String, SmtpRelayClient.RecipientResult> byRecipient(List<SmtpRelayClient.RecipientResult> results) {
        return results.stream().collect(Collectors.toMap(
                SmtpRelayClient.RecipientResult::recipient, Function.identity()));
    }

    @SuppressWarnings("unchecked")
    private static List<SmtpRelayClient.RecipientResult> classify(
            SmtpRelayClient client, SendFailedException error, List<String> recipients) throws Exception {
        Method method = SmtpRelayClient.class.getDeclaredMethod(
                "classifySendFailed", SendFailedException.class, List.class, String.class);
        method.setAccessible(true);
        return (List<SmtpRelayClient.RecipientResult>) method.invoke(client, error, recipients, "127.0.0.1");
    }
}
