package ru.corvex.mailstorage.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.mime.OutgoingMessageFactory;
import ru.corvex.mailstorage.pipeline.MailEventPublisher;
import ru.corvex.mailstorage.pipeline.MailPipelineEvents;
import ru.corvex.mailstorage.search.MessageSearchIndexer;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSendServiceTest {

    private static final Set<String> ALL = Set.of("MAIL_SEND_INTERNAL", "MAIL_SEND_EXTERNAL", "MAIL_BULK_SEND");

    @TempDir
    Path tmp;

    private final UUID senderId = UUID.randomUUID();
    private AuthUserClient.MailboxOwner sender;
    private AuthUserClient auth;
    private OutgoingMessageFactory factory;
    private RawMessageSpool spool;
    private MailDeliveryService delivery;
    private MailEventPublisher events;
    private MessageSearchIndexer indexer;
    private ExternalMailAccountService externalAccounts;
    private MailSendService service;

    @BeforeEach
    void setUp() throws Exception {
        sender = new AuthUserClient.MailboxOwner(
                senderId, "sender@company.local", "Sender Name", "ACTIVE", 0);
        auth = mock(AuthUserClient.class);
        factory = mock(OutgoingMessageFactory.class);
        spool = new RawMessageSpool(tmp.resolve("spool"), 1_000_000);
        delivery = mock(MailDeliveryService.class);
        events = mock(MailEventPublisher.class);
        indexer = mock(MessageSearchIndexer.class);
        externalAccounts = mock(ExternalMailAccountService.class);
        service = service(spool);
        when(auth.findById(senderId)).thenReturn(Optional.of(sender));
        when(factory.compose(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenAnswer(inv -> new OutgoingMessageFactory.ComposedMessage(
                        "raw-eml".getBytes(StandardCharsets.UTF_8), inv.getArgument(2)));
    }

    @Test
    void rejectsMissingOrInactiveSender() {
        when(auth.findById(senderId)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, request(List.of("a@company.local"), List.of(), List.of(), null, null)))
                .getStatusCode());

        when(auth.findById(senderId)).thenReturn(Optional.of(
                new AuthUserClient.MailboxOwner(senderId, "sender@company.local", "S", "BLOCKED", 0)));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, request(List.of("a@company.local"), List.of(), List.of(), null, null)))
                .getStatusCode());
    }

    @Test
    void validatesRecipientsAndRecipientCount() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, request(List.of(" "), null, null, null, null))).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, request(List.of("bad@"), List.of(), List.of(), null, null)))
                .getStatusCode());

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add("u" + i + "@company.local");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, request(many, List.of(), List.of(), null, null))).getStatusCode());
    }

    @Test
    void enforcesInternalExternalAndBulkPermissionsSeparately() {
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, Set.of("MAIL_SEND_EXTERNAL"),
                        request(List.of("a@company.local"), List.of(), List.of(), null, null))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, Set.of("MAIL_SEND_INTERNAL"),
                        request(List.of("a@partner.test"), List.of(), List.of(), null, null))).getStatusCode());

        List<String> bulk = new ArrayList<>();
        for (int i = 0; i < 26; i++) bulk.add("u" + i + "@company.local");
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, Set.of("MAIL_SEND_INTERNAL", "MAIL_SEND_EXTERNAL"),
                        request(bulk, List.of(), List.of(), null, null))).getStatusCode());
    }

    @Test
    void sendsLocalAndExternalCopiesAndSavesSent() throws Exception {
        MailDeliveryService.DeliveryResult saved = deliveryResult(UUID.randomUUID());
        when(delivery.storeSentCopy(eq(sender), anyString(), anyLong())).thenReturn(Optional.of(saved));
        MailDtos.SendMessageRequest req = request(
                List.of("Person <USER@company.local>", "user@company.local", "outside@partner.test"),
                List.of("copy@example.internal"),
                List.of("hidden@company.local"),
                true, null);

        MailDtos.SendMessageResult result = service.send(senderId, ALL, req);

        assertEquals(List.of("user@company.local", "copy@example.internal", "hidden@company.local"),
                result.localRecipients());
        assertEquals(List.of("outside@partner.test"), result.externalRecipients());
        assertEquals(saved.messageId(), result.sentMessageId());
        assertEquals("sender@company.local", result.fromAddress());
        assertNull(result.externalAccountId());
        assertTrue(result.messageIdHeader().endsWith("@company.local>"));

        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishConfirmed(anyString(), payloads.capture(), anyString());
        assertTrue(payloads.getAllValues().get(0) instanceof MailPipelineEvents.InboundReceivedEvent);
        assertTrue(payloads.getAllValues().get(1) instanceof MailPipelineEvents.OutboundQueuedEvent);
        verify(indexer).index(saved);
    }

    @Test
    void canSkipSentCopyAndToleratesDeliveryReturningEmpty() throws Exception {
        MailDtos.SendMessageRequest noSent = request(
                List.of("a@company.local"), List.of(), List.of(), false, null);
        assertNull(service.send(senderId, ALL, noSent).sentMessageId());
        verify(delivery, never()).storeSentCopy(any(), anyString(), anyLong());

        when(delivery.storeSentCopy(eq(sender), anyString(), anyLong())).thenReturn(Optional.empty());
        MailDtos.SendMessageRequest withSent = request(
                List.of("a@partner.test"), List.of(), List.of(), null, null);
        assertNull(service.send(senderId, ALL, withSent).sentMessageId());
        verify(indexer, never()).index(any());
    }

    @Test
    void publishFailureDeletesQueuedRawCopy() throws Exception {
        AtomicReference<String> rawRef = new AtomicReference<>();
        doAnswer(inv -> {
            rawRef.set(inv.getArgument(2));
            throw new IllegalStateException("broker down");
        }).when(events).publishConfirmed(eq("mail.inbound.received"), any(), anyString());
        assertThrows(IllegalStateException.class, () -> service.send(senderId, ALL,
                request(List.of("a@company.local"), List.of(), List.of(), true, null)));
        assertFalse(spool.exists(rawRef.get()));
        verify(delivery, never()).storeSentCopy(any(), anyString(), anyLong());
    }

    @Test
    void mapsFactoryFailuresToStableHttpErrors() throws Exception {
        MailDtos.SendMessageRequest req = request(
                List.of("a@company.local"), List.of(), List.of(), false, null);
        when(factory.compose(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new AddressException("bad"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, req)).getStatusCode());

        when(factory.compose(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("bad base64"));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, req)).getStatusCode());

        when(factory.compose(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new MessagingException("mime"));
        assertThrows(IllegalStateException.class, () -> service.send(senderId, ALL, req));
    }

    @Test
    void oversizeComposedMessageBecomesPayloadTooLarge() {
        MailSendService small = service(new RawMessageSpool(tmp.resolve("small-spool"), 3));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> small.send(senderId, ALL,
                        request(List.of("a@company.local"), List.of(), List.of(), false, null)));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
    }

    @Test
    void externalAccountMustBelongToSenderAndBeEnabledForSending() throws Exception {
        UUID accountId = UUID.randomUUID();
        ExternalMailAccount foreign = externalAccount(UUID.randomUUID());
        foreign.setId(accountId);
        when(externalAccounts.accessibleAccount(senderId, ALL, accountId)).thenReturn(foreign);
        MailDtos.SendMessageRequest req = request(
                List.of("a@partner.test"), List.of(), List.of(), false, accountId);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, req)).getStatusCode());

        ExternalMailAccount own = externalAccount(senderId);
        own.setId(accountId);
        own.setSendEnabled(false);
        when(externalAccounts.accessibleAccount(senderId, ALL, accountId)).thenReturn(own);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> service.send(senderId, ALL, req)).getStatusCode());
    }

    @Test
    void externalSendAsUsesProviderQueueAndAudits() throws Exception {
        UUID accountId = UUID.randomUUID();
        ExternalMailAccount own = externalAccount(senderId);
        own.setId(accountId);
        own.setDisplayName(" ");
        when(externalAccounts.accessibleAccount(senderId, ALL, accountId)).thenReturn(own);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);

        MailDtos.SendMessageResult result = service.send(senderId, ALL, request(
                List.of("a@partner.test"), List.of(), List.of(), false, accountId));

        assertEquals("external@mail.test", result.fromAddress());
        assertEquals(accountId, result.externalAccountId());
        assertTrue(result.messageIdHeader().endsWith("@mail.test>"));
        verify(events).publishConfirmed(eq("mail.outbound.external.queued"), payload.capture(), anyString());
        MailPipelineEvents.ExternalOutboundQueuedEvent event =
                (MailPipelineEvents.ExternalOutboundQueuedEvent) payload.getValue();
        assertEquals(accountId, event.externalAccountId());
        assertEquals(senderId, event.senderId());
        assertTrue(spool.exists(event.rawRef()));
        verify(externalAccounts).audit(eq(senderId), eq(own), eq("EXTERNAL_SEND_AS_QUEUED"), anyString());
        verify(factory).compose(eq("external@mail.test"), eq("Sender Name"), anyString(), any(), any(), any());
    }

    @Test
    void externalAccountLocalRecipientsStillUseInboundQueue() throws Exception {
        UUID accountId = UUID.randomUUID();
        ExternalMailAccount own = externalAccount(senderId);
        own.setId(accountId);
        own.setDisplayName("External Name");
        when(externalAccounts.accessibleAccount(senderId, ALL, accountId)).thenReturn(own);

        var result = service.send(senderId, ALL, request(
                List.of("a@company.local"), List.of(), List.of(), false, accountId));

        assertEquals(List.of("a@company.local"), result.localRecipients());
        verify(events).publishConfirmed(eq("mail.inbound.received"), any(), anyString());
        verify(events, never()).publishConfirmed(eq("mail.outbound.external.queued"), any(), anyString());
        verify(factory).compose(eq("external@mail.test"), eq("External Name"), anyString(), any(), any(), any());
    }

    private MailSendService service(RawMessageSpool spoolToUse) {
        return new MailSendService(auth, factory, spoolToUse, delivery, events, indexer, externalAccounts,
                " Company.Local, Example.Internal ");
    }

    private MailDeliveryService.DeliveryResult deliveryResult(UUID id) {
        MimeMailParser.ParsedMail parsed = new MimeMailParser.ParsedMail(
                "<sent@x>", sender.email(), "subject", "snippet", "body", null, Instant.now(),
                List.of(), List.of(), List.of());
        return new MailDeliveryService.DeliveryResult(id, senderId, UUID.randomUUID(), 1, sender.email(), parsed);
    }

    private ExternalMailAccount externalAccount(UUID ownerId) {
        ExternalMailAccount account = new ExternalMailAccount();
        account.setOwnerId(ownerId);
        account.setEmailAddress("external@mail.test");
        account.setDisplayName("External");
        account.setEnabled(true);
        account.setSendEnabled(true);
        account.setSmtpEnabled(true);
        return account;
    }

    private static MailDtos.SendMessageRequest request(
            List<String> to, List<String> cc, List<String> bcc, Boolean saveToSent, UUID externalAccountId) {
        return new MailDtos.SendMessageRequest(to, cc, bcc, "Subject", "Body", null, List.of(), saveToSent,
                externalAccountId);
    }
}
