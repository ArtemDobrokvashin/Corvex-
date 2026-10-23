package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.subethamail.smtp.AuthenticationHandler;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.RejectException;
import ru.corvex.mailsmtp.outbound.OutboundEvents;
import ru.corvex.mailsmtp.outbound.StorageRawClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubmissionMessageHandlerFactoryTest {

    private static final UUID USER_ID = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final byte[] EML = "From: alice@company.local\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private SubmissionIdentityClient identities;
    private InboundMessageHandlerFactory.RawMessageStoreClient storage;
    private StorageRawClient rawClient;
    private ConfirmedRabbit rabbit;

    @BeforeEach
    void setUp() {
        identities = mock(SubmissionIdentityClient.class);
        storage = mock(InboundMessageHandlerFactory.RawMessageStoreClient.class);
        rawClient = mock(StorageRawClient.class);
        rabbit = new ConfirmedRabbit();
    }

    @Test
    void requiresCachedAuthenticatedIdentityForFromRecipientAndData() throws Exception {
        when(identities.lookupCached(anyString())).thenReturn(Optional.empty());
        MessageHandler noCached = factory(address -> decision(true)).create(context("alice@company.local"));
        assertReject(530, "Authentication required", () -> noCached.from("alice@company.local"));
        assertReject(530, "Authentication required", () -> noCached.recipient("bob@example.org"));
        assertReject(530, "Authentication required", () -> noCached.data(stream()));

        MessageHandler noAuthHandler = factory(address -> decision(true)).create(contextWithoutAuth());
        assertReject(530, "Authentication required", () -> noAuthHandler.from("alice@company.local"));

        MessageHandler nullIdentity = factory(address -> decision(true)).create(context(null));
        assertReject(530, "Authentication required", () -> nullIdentity.from("alice@company.local"));
    }

    @Test
    void fromMustBeOwnedMailboxAfterNormalization() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_INTERNAL"), address -> decision(true));

        assertReject(553, "not owned by user", () -> handler.from("other@company.local"));
        handler.from("Alice <ALICE@COMPANY.LOCAL>");
    }

    @Test
    void recipientPermissionsLocalPolicyAndDuplicateHandling() throws Exception {
        AtomicInteger policyCalls = new AtomicInteger();
        InboundMessageHandlerFactory.RecipientPolicy policy = address -> {
            policyCalls.incrementAndGet();
            if (address.startsWith("temp@")) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL;
            }
            if (address.startsWith("relay@")) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_RELAY;
            }
            if (address.startsWith("unknown@")) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER;
            }
            return InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT;
        };
        MessageHandler handler = handler(mailbox("MAIL_SEND_INTERNAL", "MAIL_SEND_EXTERNAL"), policy);
        handler.from("alice@company.local");

        handler.recipient("<BOB@COMPANY.LOCAL>");
        handler.recipient("bob@company.local");
        assertEquals(1, policyCalls.get(), "duplicate normalized RCPT must not be rechecked");
        handler.recipient("user@branch.local");
        assertEquals(2, policyCalls.get());
        assertReject(451, "Temporary local problem", () -> handler.recipient("temp@company.local"));
        assertReject(550, "No such user here", () -> handler.recipient("relay@company.local"));
        assertReject(550, "No such user here", () -> handler.recipient("unknown@company.local"));
        handler.recipient("partner@example.org");

        MessageHandler noInternal = handler(mailbox("MAIL_SEND_EXTERNAL"), policy);
        noInternal.from("alice@company.local");
        int before = policyCalls.get();
        assertReject(550, "Relay denied", () -> noInternal.recipient("bob@company.local"));
        assertEquals(before, policyCalls.get());

        MessageHandler noExternal = handler(mailbox("MAIL_SEND_INTERNAL"), policy);
        noExternal.from("alice@company.local");
        assertReject(550, "Relay denied", () -> noExternal.recipient("partner@example.org"));
    }

    @Test
    void recipientLimitsRespectBulkPermissionAndAbsoluteMaximum() throws Exception {
        MessageHandler limited = handler(mailbox("MAIL_SEND_EXTERNAL"), address -> decision(true));
        limited.from("alice@company.local");
        for (int i = 0; i < 25; i++) {
            limited.recipient("user" + i + "@example.org");
        }
        assertReject(552, "Too many recipients", () -> limited.recipient("user25@example.org"));

        MessageHandler bulk = handler(mailbox("MAIL_SEND_EXTERNAL", "MAIL_BULK_SEND"), address -> decision(true));
        bulk.from("alice@company.local");
        for (int i = 0; i < 100; i++) {
            bulk.recipient("bulk" + i + "@example.org");
        }
        assertReject(552, "Too many recipients", () -> bulk.recipient("bulk100@example.org"));
    }

    @Test
    void localOnlyDataPublishesInboundEvent() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_INTERNAL"), address -> decision(true));
        handler.from("alice@company.local");
        handler.recipient("bob@company.local");
        when(storage.storeRaw(any(InputStream.class)))
                .thenReturn(stored("local-ref", "sha-local", 111L));

        assertNull(handler.data(stream()));

        assertEquals(1, rabbit.published.size());
        ConfirmedRabbit.Published published = rabbit.published.get(0);
        assertEquals(InboundMessageHandlerFactory.ROUTING_RECEIVED, published.routingKey());
        assertEquals("local-ref:mail.inbound.received", published.correlation().getId());
        var event = mapper.readValue(published.payload(), InboundMessageHandlerFactory.InboundReceivedEvent.class);
        assertEquals("local-ref", event.rawRef());
        assertEquals("alice@company.local", event.mailFrom());
        assertEquals(List.of("bob@company.local"), event.recipients());
        assertEquals(111L, event.sizeBytes());
        assertEquals("sha-local", event.sha256());
        verify(rawClient, never()).download(anyString());
    }

    @Test
    void externalOnlyDataPublishesOutboundEvent() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_EXTERNAL"), address -> decision(true));
        handler.from("alice@company.local");
        handler.recipient("partner@example.org");
        when(storage.storeRaw(any(InputStream.class)))
                .thenReturn(stored("out-ref", "sha-out", 222L));

        assertNull(handler.data(stream()));

        assertEquals(1, rabbit.published.size());
        ConfirmedRabbit.Published published = rabbit.published.get(0);
        assertEquals("mail.outbound.queued", published.routingKey());
        assertEquals("out-ref:mail.outbound.queued", published.correlation().getId());
        OutboundEvents.OutboundQueuedEvent event = mapper.readValue(published.payload(), OutboundEvents.OutboundQueuedEvent.class);
        assertEquals("out-ref", event.rawRef());
        assertEquals(List.of("partner@example.org"), event.recipients());
        assertNull(event.messageIdHeader());
        assertEquals(USER_ID, event.senderId());
        assertEquals(1, event.attempt());
        verify(rawClient, never()).download(anyString());
    }

    @Test
    void mixedDataCopiesSpoolAndPublishesBothEvents() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_INTERNAL", "MAIL_SEND_EXTERNAL"), address -> decision(true));
        handler.from("alice@company.local");
        handler.recipient("bob@branch.local");
        handler.recipient("partner@example.org");
        List<byte[]> storedBodies = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        when(storage.storeRaw(any(InputStream.class))).thenAnswer(invocation -> {
            InputStream input = invocation.getArgument(0);
            storedBodies.add(input.readAllBytes());
            return calls.incrementAndGet() == 1
                    ? stored("local-ref", "sha-local", 111L)
                    : stored("out-ref", "sha-out", 222L);
        });
        when(rawClient.download("local-ref")).thenReturn(EML);

        assertNull(handler.data(stream()));

        assertEquals(2, storedBodies.size());
        assertEquals(new String(EML, StandardCharsets.UTF_8), new String(storedBodies.get(0), StandardCharsets.UTF_8));
        assertEquals(new String(EML, StandardCharsets.UTF_8), new String(storedBodies.get(1), StandardCharsets.UTF_8));
        assertEquals(List.of("mail.inbound.received", "mail.outbound.queued"),
                rabbit.published.stream().map(ConfirmedRabbit.Published::routingKey).toList());
        var inbound = mapper.readValue(rabbit.published.get(0).payload(), InboundMessageHandlerFactory.InboundReceivedEvent.class);
        var outbound = mapper.readValue(rabbit.published.get(1).payload(), OutboundEvents.OutboundQueuedEvent.class);
        assertEquals(List.of("bob@branch.local"), inbound.recipients());
        assertEquals(List.of("partner@example.org"), outbound.recipients());
    }

    @Test
    void initialStorageFailuresMapTo552And451() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_EXTERNAL"), address -> decision(true));
        handler.from("alice@company.local");
        handler.recipient("partner@example.org");

        when(storage.storeRaw(any(InputStream.class))).thenThrow(
                new InboundMessageHandlerFactory.RawMessageStoreClient.MessageTooLargeException("large"));
        assertReject(552, "Message exceeds maximum size", () -> handler.data(stream()));

        when(storage.storeRaw(any(InputStream.class))).thenThrow(new IOException("down"));
        assertReject(451, "Temporary local problem", () -> handler.data(stream()));
        verifyNoInteractions(rawClient);
        assertTrue(rabbit.published.isEmpty());
    }

    @Test
    void mixedCopyFailureDeletesFirstSpoolAndReturns451() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_INTERNAL", "MAIL_SEND_EXTERNAL"), address -> decision(true));
        handler.from("alice@company.local");
        handler.recipient("bob@company.local");
        handler.recipient("partner@example.org");
        when(storage.storeRaw(any(InputStream.class))).thenReturn(stored("local-ref", "sha", 1L));
        when(rawClient.download("local-ref")).thenThrow(new IOException("copy unavailable"));

        assertReject(451, "Temporary local problem", () -> handler.data(stream()));

        verify(storage).deleteRaw("local-ref");
        assertTrue(rabbit.published.isEmpty());
    }

    @Test
    void secondPublishFailureDeletesBothMixedSpoolCopies() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_INTERNAL", "MAIL_SEND_EXTERNAL"), address -> decision(true));
        handler.from("alice@company.local");
        handler.recipient("bob@company.local");
        handler.recipient("partner@example.org");
        when(storage.storeRaw(any(InputStream.class)))
                .thenReturn(stored("local-ref", "sha-local", 1L), stored("out-ref", "sha-out", 2L));
        when(rawClient.download("local-ref")).thenReturn(EML);
        rabbit.nackOnCall = 2;

        assertReject(451, "Temporary local problem", () -> handler.data(stream()));

        assertEquals(2, rabbit.published.size());
        verify(storage).deleteRaw("local-ref");
        verify(storage).deleteRaw("out-ref");
    }

    @Test
    void dataRequiresFromAndRecipientsAndDoneClearsState() throws Exception {
        MessageHandler handler = handler(mailbox("MAIL_SEND_EXTERNAL"), address -> decision(true));
        assertReject(554, "No valid recipients", () -> handler.data(stream()));

        handler.from("alice@company.local");
        assertReject(554, "No valid recipients", () -> handler.data(stream()));

        handler.recipient("partner@example.org");
        when(storage.storeRaw(any(InputStream.class))).thenReturn(stored("out-ref", "sha", 1L));
        assertNull(handler.data(stream()));
        handler.done();
        assertReject(554, "No valid recipients", () -> handler.data(stream()));
    }

    private MessageHandler handler(SubmissionIdentityClient.AuthenticatedMailbox mailbox,
                                   InboundMessageHandlerFactory.RecipientPolicy policy) throws Exception {
        when(identities.lookupCached(anyString())).thenReturn(Optional.ofNullable(mailbox));
        return factory(policy).create(context(" Alice@Company.Local "));
    }

    private SubmissionMessageHandlerFactory factory(InboundMessageHandlerFactory.RecipientPolicy policy) {
        return new SubmissionMessageHandlerFactory(
                identities, policy, storage, rawClient, rabbit.rabbit, mapper, " Company.Local, Branch.Local ");
    }

    private MessageContext context(Object identity) throws Exception {
        MessageContext context = mock(MessageContext.class);
        AuthenticationHandler auth = mock(AuthenticationHandler.class);
        when(auth.getIdentity()).thenReturn(identity);
        when(context.getAuthenticationHandler()).thenReturn(Optional.of(auth));
        when(context.getRemoteAddress()).thenReturn(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 51_000));
        return context;
    }

    private MessageContext contextWithoutAuth() {
        MessageContext context = mock(MessageContext.class);
        when(context.getAuthenticationHandler()).thenReturn(Optional.empty());
        return context;
    }

    private static SubmissionIdentityClient.AuthenticatedMailbox mailbox(String... permissions) {
        return new SubmissionIdentityClient.AuthenticatedMailbox(
                USER_ID, "alice@company.local", "alice@company.local", "Alice", Set.of(permissions));
    }

    private static InboundMessageHandlerFactory.RawMessageStoreClient.StoredRaw stored(String ref, String sha, long size) {
        return new InboundMessageHandlerFactory.RawMessageStoreClient.StoredRaw(ref, sha, size);
    }

    private static InboundMessageHandlerFactory.RecipientPolicy.Decision decision(boolean accept) {
        return accept
                ? InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT
                : InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER;
    }

    private static ByteArrayInputStream stream() {
        return new ByteArrayInputStream(EML);
    }

    private static void assertReject(int code, String messagePart, Executable action) {
        RejectException error = assertThrows(RejectException.class, action);
        assertEquals(code, error.getCode());
        assertTrue(error.getMessage().contains(messagePart), error.getMessage());
    }
}
