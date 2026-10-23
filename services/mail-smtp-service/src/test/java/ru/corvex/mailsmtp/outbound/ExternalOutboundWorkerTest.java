package ru.corvex.mailsmtp.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExternalOutboundWorkerTest {

    private static final String RAW_REF = "raw-external-1";
    private static final String FROM = "old@example.ru";
    private static final UUID ACCOUNT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SENDER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final byte[] EML = "From: old@example.ru\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private StorageRawClient raw;
    private ExternalAccountConfigClient configs;
    private ExternalSmtpClient smtp;
    private RabbitPublishes publishes;
    private ExternalOutboundWorker worker;
    private ExternalAccountConfigClient.TransportConfig config;

    @BeforeEach
    void setUp() {
        raw = mock(StorageRawClient.class);
        configs = mock(ExternalAccountConfigClient.class);
        smtp = mock(ExternalSmtpClient.class);
        publishes = new RabbitPublishes();
        worker = new ExternalOutboundWorker(mapper, raw, configs, smtp, publishes.rabbit);
        config = new ExternalAccountConfigClient.TransportConfig(
                ACCOUNT_ID, SENDER_ID, FROM, "Old", "CUSTOM", "IMAP", "imap.example.ru", 993, "SSL_TLS",
                "old@example.ru", "secret", "smtp.example.ru", 465, "SSL_TLS", null, true, "Sent");
    }

    @Test
    void missingSpoolBouncesEveryRecipientWithoutFetchingConfig() throws Exception {
        when(raw.download(RAW_REF)).thenThrow(new StorageRawClient.RawNotFoundException(RAW_REF));

        worker.onExternalQueued(payload(event(List.of("a@example.com", "b@example.com"), Instant.now(), 1)));

        assertEquals(2, bounced().size());
        assertTrue(bounced().stream().allMatch(p -> read(p, OutboundEvents.OutboundBouncedEvent.class)
                .diagnostic().contains("потеряно из spool")));
        verifyNoInteractions(configs, smtp);
        verify(raw, never()).delete(anyString());
    }

    @Test
    void storageFailureSchedulesExternalRetry() throws Exception {
        when(raw.download(RAW_REF)).thenThrow(new IOException("down"));

        worker.onExternalQueued(payload(event(List.of("a@example.com"), Instant.now(), 3)));

        RabbitPublishes.Published retry = onlyRetry();
        assertEquals("mail.outbound.external.retry.1h", retry.first());
        OutboundEvents.ExternalOutboundQueuedEvent retried = read(retry, OutboundEvents.ExternalOutboundQueuedEvent.class);
        assertEquals(4, retried.attempt());
        assertEquals(ACCOUNT_ID, retried.externalAccountId());
        verify(raw, never()).delete(anyString());
    }

    @Test
    void permanentConfigErrorBouncesAndDeletesSpool() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(configs.fetch(ACCOUNT_ID)).thenThrow(
                new ExternalAccountConfigClient.ConfigException("account disabled", true));

        worker.onExternalQueued(payload(event(List.of("a@example.com", "b@example.com"), Instant.now(), 1)));

        assertEquals(2, bounced().size());
        assertTrue(bounced().stream().allMatch(p -> "account disabled"
                .equals(read(p, OutboundEvents.OutboundBouncedEvent.class).diagnostic())));
        verify(raw).delete(RAW_REF);
        verifyNoInteractions(smtp);
    }

    @Test
    void temporaryConfigAndPlainIoErrorsScheduleRetry() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(configs.fetch(ACCOUNT_ID)).thenThrow(
                new ExternalAccountConfigClient.ConfigException("storage busy", false));

        worker.onExternalQueued(payload(event(List.of("a@example.com"), Instant.now(), 1)));

        assertEquals("mail.outbound.external.retry.5m", onlyRetry().first());
        assertTrue(bounced().isEmpty());
        verify(raw, never()).delete(anyString());

        setUp();
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(configs.fetch(ACCOUNT_ID)).thenThrow(new IOException("connection reset"));

        worker.onExternalQueued(payload(event(List.of("a@example.com"), Instant.now(), 2)));

        assertEquals("mail.outbound.external.retry.15m", onlyRetry().first());
        verify(raw, never()).delete(anyString());
    }

    @Test
    void mixedProviderResultsPublishSentAppendBounceAndRetryTemporary() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(configs.fetch(ACCOUNT_ID)).thenReturn(config);
        when(smtp.send(config, EML, FROM, List.of("a@example.com", "b@example.com", "c@example.com")))
                .thenReturn(List.of(
                        result("a@example.com", SmtpRelayClient.Status.SENT, "250 OK"),
                        result("b@example.com", SmtpRelayClient.Status.PERMANENT, "550 no user"),
                        result("c@example.com", SmtpRelayClient.Status.TEMPORARY, "451 later")));

        worker.onExternalQueued(payload(event(List.of("a@example.com", "b@example.com", "c@example.com"), Instant.now(), 1)));

        assertEquals(1, publishes.unconfirmed.size());
        OutboundEvents.OutboundSentEvent sent = read(publishes.unconfirmed.get(0), OutboundEvents.OutboundSentEvent.class);
        assertEquals(List.of("a@example.com"), sent.recipients());
        verify(smtp).appendSent(config, EML);

        assertEquals(1, bounced().size());
        assertEquals("b@example.com", read(bounced().get(0), OutboundEvents.OutboundBouncedEvent.class).recipient());
        OutboundEvents.ExternalOutboundQueuedEvent retry = read(onlyRetry(), OutboundEvents.ExternalOutboundQueuedEvent.class);
        assertEquals(List.of("c@example.com"), retry.recipients());
        verify(raw, never()).delete(anyString());
    }

    @Test
    void allTerminalProviderResultsDeleteSpool() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(configs.fetch(ACCOUNT_ID)).thenReturn(config);
        when(smtp.send(config, EML, FROM, List.of("a@example.com", "b@example.com")))
                .thenReturn(List.of(
                        result("a@example.com", SmtpRelayClient.Status.SENT, "250 OK"),
                        result("b@example.com", SmtpRelayClient.Status.PERMANENT, "550 no user")));

        worker.onExternalQueued(payload(event(List.of("a@example.com", "b@example.com"), Instant.now(), 1)));

        assertTrue(retries().isEmpty());
        verify(smtp).appendSent(config, EML);
        verify(raw).delete(RAW_REF);
    }

    @Test
    void expiredTemporaryResultBouncesAndDeletesWithoutAppend() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(configs.fetch(ACCOUNT_ID)).thenReturn(config);
        when(smtp.send(config, EML, FROM, List.of("a@example.com")))
                .thenReturn(List.of(result("a@example.com", SmtpRelayClient.Status.TEMPORARY, "451 grey listed")));
        Instant old = Instant.now().minusSeconds(73L * 3_600L);

        worker.onExternalQueued(payload(event(List.of("a@example.com"), old, 9)));

        OutboundEvents.OutboundBouncedEvent bounced = read(bounced().get(0), OutboundEvents.OutboundBouncedEvent.class);
        assertTrue(bounced.diagnostic().contains("72 часа"));
        assertTrue(bounced.diagnostic().contains("grey listed"));
        verify(smtp, never()).appendSent(config, EML);
        verify(raw).delete(RAW_REF);
    }

    @Test
    void emptyProviderResultDeletesSpool() throws Exception {
        when(raw.download(RAW_REF)).thenReturn(EML);
        when(configs.fetch(ACCOUNT_ID)).thenReturn(config);
        when(smtp.send(config, EML, FROM, List.of("a@example.com"))).thenReturn(List.of());

        worker.onExternalQueued(payload(event(List.of("a@example.com"), Instant.now(), 1)));

        assertTrue(publishes.confirmed.isEmpty());
        assertTrue(publishes.unconfirmed.isEmpty());
        verify(smtp, never()).appendSent(config, EML);
        verify(raw).delete(RAW_REF);
    }

    @Test
    void nackAndReturnedConfirmsPropagateAsIOException() throws Exception {
        when(raw.download(RAW_REF)).thenThrow(new StorageRawClient.RawNotFoundException(RAW_REF));
        publishes.mode = RabbitPublishes.Mode.NACK;

        IOException nack = assertThrows(IOException.class,
                () -> worker.onExternalQueued(payload(event(List.of("a@example.com"), Instant.now(), 1))));
        assertTrue(nack.getMessage().contains("nack"));

        publishes.mode = RabbitPublishes.Mode.RETURNED;
        IOException returned = assertThrows(IOException.class,
                () -> worker.onExternalQueued(payload(event(List.of("a@example.com"), Instant.now(), 1))));
        assertTrue(returned.getMessage().contains("немаршрутизируемое"));
    }

    @Test
    void malformedPayloadFailsBeforeCollaborators() {
        assertThrows(IOException.class, () -> worker.onExternalQueued("[]"));

        verifyNoInteractions(raw, configs, smtp);
        assertTrue(publishes.confirmed.isEmpty());
    }

    private OutboundEvents.ExternalOutboundQueuedEvent event(List<String> recipients, Instant queuedAt, int attempt) {
        return new OutboundEvents.ExternalOutboundQueuedEvent(
                RAW_REF, ACCOUNT_ID, FROM, recipients, "<ext-1@example.ru>", SENDER_ID, queuedAt, attempt);
    }

    private String payload(OutboundEvents.ExternalOutboundQueuedEvent event) throws IOException {
        return mapper.writeValueAsString(event);
    }

    private static SmtpRelayClient.RecipientResult result(String recipient, SmtpRelayClient.Status status, String diagnostic) {
        return new SmtpRelayClient.RecipientResult(recipient, status, diagnostic, "smtp.example.ru");
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
