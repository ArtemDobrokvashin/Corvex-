package ru.corvex.mailsmtp.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboundWorkerTest {

    private static final String RAW_REF = "raw-out-1";
    private static final String FROM = "alice@company.local";
    private static final UUID SENDER_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final byte[] EML = "Subject: hi\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SIGNED = "DKIM-Signature: test\r\nSubject: hi\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private StorageRawClient raw;
    private DkimSigner dkim;
    private SmtpRelayClient relay;
    private RabbitPublishes publishes;
    private OutboundWorker worker;

    @BeforeEach
    void setUp() {
        raw = mock(StorageRawClient.class);
        dkim = mock(DkimSigner.class);
        relay = mock(SmtpRelayClient.class);
        publishes = new RabbitPublishes();
        worker = new OutboundWorker(mapper, raw, dkim, relay, publishes.rabbit);
    }

    @Test
    void missingSpoolBouncesEveryRecipientWithConfirmedEvents() throws Exception {
        when(raw.download(RAW_REF)).thenThrow(new StorageRawClient.RawNotFoundException(RAW_REF));

        worker.onOutboundQueued(payload(event(List.of("a@example.com", "b@example.org"), Instant.now(), 1)));

        List<RabbitPublishes.Published> bounced = bounced();
        assertEquals(2, bounced.size());
        assertEquals(List.of("a@example.com", "b@example.org"), bounced.stream()
                .map(p -> read(p, OutboundEvents.OutboundBouncedEvent.class).recipient()).toList());
        assertTrue(bounced.stream().allMatch(p -> read(p, OutboundEvents.OutboundBouncedEvent.class)
                .diagnostic().contains("потеряно из spool")));
        assertEquals(RAW_REF + ":a@example.com:mail.outbound.bounced", bounced.get(0).correlation().getId());
        verify(raw, never()).delete(anyString());
        verifyNoInteractions(dkim, relay);
    }

    @Test
    void storageIOExceptionSchedulesRetryAndKeepsSpool() throws Exception {
        when(raw.download(RAW_REF)).thenThrow(new IOException("storage down"));

        worker.onOutboundQueued(payload(event(List.of("a@example.com"), Instant.now(), 0)));

        RabbitPublishes.Published retry = onlyRetry();
        assertEquals("mail.outbound.retry.5m", retry.first());
        OutboundEvents.OutboundQueuedEvent retried = read(retry, OutboundEvents.OutboundQueuedEvent.class);
        assertEquals(1, retried.attempt());
        assertEquals(List.of("a@example.com"), retried.recipients());
        assertEquals(RAW_REF + ":mail.outbound.retry.5m", retry.correlation().getId());
        verify(raw, never()).delete(anyString());
    }

    @Test
    void dkimRuntimeFailureSchedulesNextRetryTier() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(dkim.signIfConfigured(EML, FROM)).thenThrow(new IllegalStateException("bad key"));

        worker.onOutboundQueued(payload(event(List.of("a@example.com"), Instant.now(), 2)));

        RabbitPublishes.Published retry = onlyRetry();
        assertEquals("mail.outbound.retry.15m", retry.first());
        assertEquals(3, read(retry, OutboundEvents.OutboundQueuedEvent.class).attempt());
        verifyNoInteractions(relay);
        verify(raw, never()).delete(anyString());
    }

    @Test
    void expiredQueueAgeBouncesAndDeletesSpool() throws Exception {
        when(raw.download(RAW_REF)).thenThrow(new IOException("storage down"));
        Instant old = Instant.now().minusSeconds(73L * 3_600L);

        worker.onOutboundQueued(payload(event(List.of("a@example.com"), old, 7)));

        OutboundEvents.OutboundBouncedEvent bounced = read(bounced().get(0), OutboundEvents.OutboundBouncedEvent.class);
        assertTrue(bounced.diagnostic().contains("72 часа"));
        assertTrue(bounced.diagnostic().contains("storage down"));
        assertTrue(retries().isEmpty());
        verify(raw).delete(RAW_REF);
    }

    @Test
    void groupsByDomainPublishesSentAndBouncedAndRetriesOnlyTemporaryRecipients() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(dkim.signIfConfigured(EML, FROM)).thenReturn(SIGNED);
        when(relay.sendToDomain(SIGNED, FROM, "example.com", List.of("a@Example.COM", "b@example.com")))
                .thenReturn(List.of(
                        result("a@Example.COM", SmtpRelayClient.Status.SENT, "250 OK"),
                        result("b@example.com", SmtpRelayClient.Status.PERMANENT, "550 no user")));
        when(relay.sendToDomain(SIGNED, FROM, "other.test", List.of("c@Other.Test", "d@other.test")))
                .thenReturn(List.of(
                        result("c@Other.Test", SmtpRelayClient.Status.TEMPORARY, "451 later"),
                        result("d@other.test", SmtpRelayClient.Status.SENT, "250 OK")));

        worker.onOutboundQueued(payload(event(
                List.of("a@Example.COM", "b@example.com", "c@Other.Test", "d@other.test"), Instant.now(), 1)));

        assertEquals(1, publishes.unconfirmed.size());
        RabbitPublishes.Published sentPublish = publishes.unconfirmed.get(0);
        assertEquals("mail.outbound.sent", sentPublish.second());
        OutboundEvents.OutboundSentEvent sent = read(sentPublish, OutboundEvents.OutboundSentEvent.class);
        assertEquals(List.of("a@Example.COM", "d@other.test"), sent.recipients());
        assertEquals(SENDER_ID, sent.senderId());

        assertEquals(1, bounced().size());
        OutboundEvents.OutboundBouncedEvent bounced = read(bounced().get(0), OutboundEvents.OutboundBouncedEvent.class);
        assertEquals("b@example.com", bounced.recipient());
        assertEquals("550 no user", bounced.diagnostic());

        OutboundEvents.OutboundQueuedEvent retry = read(onlyRetry(), OutboundEvents.OutboundQueuedEvent.class);
        assertEquals(List.of("c@Other.Test"), retry.recipients());
        assertEquals(2, retry.attempt());
        verify(raw, never()).delete(anyString());
        verify(relay).sendToDomain(SIGNED, FROM, "example.com", List.of("a@Example.COM", "b@example.com"));
        verify(relay).sendToDomain(SIGNED, FROM, "other.test", List.of("c@Other.Test", "d@other.test"));
    }

    @Test
    void deletesSpoolWhenAllRecipientsAreTerminal() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(dkim.signIfConfigured(EML, FROM)).thenReturn(SIGNED);
        when(relay.sendToDomain(SIGNED, FROM, "example.com", List.of("a@example.com", "b@example.com")))
                .thenReturn(List.of(
                        result("a@example.com", SmtpRelayClient.Status.SENT, "250 OK"),
                        result("b@example.com", SmtpRelayClient.Status.PERMANENT, "550 no user")));

        worker.onOutboundQueued(payload(event(List.of("a@example.com", "b@example.com"), Instant.now(), 1)));

        assertTrue(retries().isEmpty());
        verify(raw).delete(RAW_REF);
    }

    @Test
    void recipientWithoutAtSignUsesEmptyDomainGroup() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(dkim.signIfConfigured(EML, FROM)).thenReturn(SIGNED);
        when(relay.sendToDomain(SIGNED, FROM, "", List.of("postmaster")))
                .thenReturn(List.of(result("postmaster", SmtpRelayClient.Status.SENT, "250 OK")));

        worker.onOutboundQueued(payload(event(List.of("postmaster"), Instant.now(), 1)));

        verify(relay).sendToDomain(SIGNED, FROM, "", List.of("postmaster"));
        verify(raw).delete(RAW_REF);
    }

    @Test
    void nackReturnedAndFailedConfirmsAbortProcessing() throws Exception {
        when(raw.download(RAW_REF)).thenThrow(new StorageRawClient.RawNotFoundException(RAW_REF));

        publishes.mode = RabbitPublishes.Mode.NACK;
        IOException nack = assertThrows(IOException.class,
                () -> worker.onOutboundQueued(payload(event(List.of("a@example.com"), Instant.now(), 1))));
        assertTrue(nack.getMessage().contains("nack"));

        publishes.mode = RabbitPublishes.Mode.RETURNED;
        IOException returned = assertThrows(IOException.class,
                () -> worker.onOutboundQueued(payload(event(List.of("a@example.com"), Instant.now(), 1))));
        assertTrue(returned.getMessage().contains("немаршрутизируемое"));

        publishes.mode = RabbitPublishes.Mode.FAIL;
        IOException failed = assertThrows(IOException.class,
                () -> worker.onOutboundQueued(payload(event(List.of("a@example.com"), Instant.now(), 1))));
        assertTrue(failed.getMessage().contains("не подтвердил"));
        assertInstanceOf(ExecutionException.class, failed.getCause());
    }

    @Test
    void malformedPayloadFailsBeforeAnyCollaboratorCall() {
        assertThrows(IOException.class, () -> worker.onOutboundQueued("{broken"));

        verifyNoInteractions(raw, dkim, relay);
        assertTrue(publishes.confirmed.isEmpty());
        assertTrue(publishes.unconfirmed.isEmpty());
    }

    private OutboundEvents.OutboundQueuedEvent event(List<String> recipients, Instant queuedAt, int attempt) {
        return new OutboundEvents.OutboundQueuedEvent(
                RAW_REF, FROM, recipients, "<out-1@company.local>", SENDER_ID, queuedAt, attempt);
    }

    private String payload(OutboundEvents.OutboundQueuedEvent event) throws IOException {
        return mapper.writeValueAsString(event);
    }

    private static SmtpRelayClient.RecipientResult result(String recipient, SmtpRelayClient.Status status, String diagnostic) {
        return new SmtpRelayClient.RecipientResult(recipient, status, diagnostic, "mx.example.net");
    }

    private List<RabbitPublishes.Published> bounced() {
        return publishes.confirmedTo(OutboundAmqpConfig.EXCHANGE, "mail.outbound.bounced");
    }

    private List<RabbitPublishes.Published> retries() {
        return publishes.confirmed.stream().filter(p -> p.second() == null).toList();
    }

    private RabbitPublishes.Published onlyRetry() {
        assertEquals(1, retries().size());
        return retries().get(0);
    }

    private <T> T read(RabbitPublishes.Published published, Class<T> type) {
        try {
            return mapper.readValue(published.payload(), type);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
