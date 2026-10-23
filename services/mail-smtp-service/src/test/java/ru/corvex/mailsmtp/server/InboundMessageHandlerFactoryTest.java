package ru.corvex.mailsmtp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.RejectException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InboundMessageHandlerFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private InboundMessageHandlerFactory.RawMessageStoreClient storage;
    private ConfirmedRabbit rabbit;
    private MessageContext context;

    @BeforeEach
    void setUp() throws Exception {
        storage = mock(InboundMessageHandlerFactory.RawMessageStoreClient.class);
        rabbit = new ConfirmedRabbit();
        context = mock(MessageContext.class);
        when(context.getRemoteAddress()).thenReturn(new InetSocketAddress(
                InetAddress.getByAddress(new byte[]{(byte) 203, 0, 113, 5}), 51_234));
    }

    @Test
    void acceptsOnlyApprovedRecipientsStoresThenPublishesConfirmedEvent() throws Exception {
        MessageHandler handler = handler(address -> address.startsWith("ok@")
                ? InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT
                : InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER);
        when(storage.storeRaw(any(InputStream.class)))
                .thenReturn(new InboundMessageHandlerFactory.RawMessageStoreClient.StoredRaw("raw-1", "sha-1", 123L));

        handler.from("sender@partner.example");
        handler.recipient("ok@company.local");
        assertReject(550, "No such user here", () -> handler.recipient("missing@company.local"));
        String response = handler.data(stream("hello"));

        assertNull(response);
        assertEquals(1, rabbit.published.size());
        ConfirmedRabbit.Published published = rabbit.published.get(0);
        assertEquals(InboundMessageHandlerFactory.EXCHANGE, published.exchange());
        assertEquals(InboundMessageHandlerFactory.ROUTING_RECEIVED, published.routingKey());
        assertEquals("raw-1", published.correlation().getId());
        InboundMessageHandlerFactory.InboundReceivedEvent event =
                mapper.readValue(published.payload(), InboundMessageHandlerFactory.InboundReceivedEvent.class);
        assertEquals("raw-1", event.rawRef());
        assertEquals("sender@partner.example", event.mailFrom());
        assertEquals(List.of("ok@company.local"), event.recipients());
        assertTrue(event.remoteAddress().contains("203.0.113.5"));
        assertEquals(123L, event.sizeBytes());
        assertEquals("sha-1", event.sha256());
        assertTrue(event.receivedAt() != null);
    }

    @Test
    void mapsRecipientPolicyDecisionsToSmtpCodes() throws Exception {
        MessageHandler handler = handler(address -> {
            if (address.startsWith("relay")) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_RELAY;
            }
            if (address.startsWith("temp")) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.TEMP_FAIL;
            }
            if (address.startsWith("unknown")) {
                return InboundMessageHandlerFactory.RecipientPolicy.Decision.REJECT_UNKNOWN_USER;
            }
            return InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT;
        });

        handler.from("sender@partner.example");
        assertReject(550, "Relay denied", () -> handler.recipient("relay@partner.example"));
        assertReject(550, "No such user here", () -> handler.recipient("unknown@company.local"));
        assertReject(451, "Temporary local problem", () -> handler.recipient("temp@company.local"));
        assertReject(554, "No valid recipients", () -> handler.data(stream("hello")));
        verifyNoInteractions(storage);
    }

    @Test
    void mapsStorageFailuresToSmtpCodes() throws Exception {
        MessageHandler handler = handler(address -> InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT);
        handler.from("sender@partner.example");
        handler.recipient("ok@company.local");

        when(storage.storeRaw(any(InputStream.class)))
                .thenThrow(new InboundMessageHandlerFactory.RawMessageStoreClient.MessageTooLargeException("too large"));
        assertReject(552, "Message exceeds maximum size", () -> handler.data(stream("large")));

        when(storage.storeRaw(any(InputStream.class))).thenThrow(new IOException("storage down"));
        assertReject(451, "Temporary local problem", () -> handler.data(stream("retry")));
        assertTrue(rabbit.published.isEmpty());
    }

    @Test
    void publishFailuresDeleteStoredRawAndReturn451() throws Exception {
        MessageHandler handler = handler(address -> InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT);
        handler.from("sender@partner.example");
        handler.recipient("ok@company.local");
        when(storage.storeRaw(any(InputStream.class)))
                .thenReturn(new InboundMessageHandlerFactory.RawMessageStoreClient.StoredRaw("raw-2", "sha-2", 10L));

        rabbit.mode = ConfirmedRabbit.Mode.NACK;
        assertReject(451, "Temporary local problem", () -> handler.data(stream("hello")));
        verify(storage).deleteRaw("raw-2");

        rabbit.mode = ConfirmedRabbit.Mode.RETURNED;
        assertReject(451, "Temporary local problem", () -> handler.data(stream("hello again")));

        rabbit.mode = ConfirmedRabbit.Mode.FAIL;
        assertReject(451, "Temporary local problem", () -> handler.data(stream("hello third")));
        assertEquals(3, rabbit.published.size());
    }

    @Test
    void doneClearsTransactionState() throws Exception {
        MessageHandler handler = handler(address -> InboundMessageHandlerFactory.RecipientPolicy.Decision.ACCEPT);
        handler.from("sender@partner.example");
        handler.recipient("ok@company.local");
        when(storage.storeRaw(any(InputStream.class)))
                .thenReturn(new InboundMessageHandlerFactory.RawMessageStoreClient.StoredRaw("raw-3", "sha-3", 4L));
        assertNull(handler.data(stream("done")));

        handler.done();

        assertReject(554, "No valid recipients", () -> handler.data(stream("after done")));
    }

    @Test
    void rawStoreDefaultDeleteIsNoOp() {
        InboundMessageHandlerFactory.RawMessageStoreClient minimal = eml ->
                new InboundMessageHandlerFactory.RawMessageStoreClient.StoredRaw("raw", "sha", 0L);

        assertDoesNotThrow(() -> minimal.deleteRaw("raw"));
    }

    private MessageHandler handler(InboundMessageHandlerFactory.RecipientPolicy policy) {
        return new InboundMessageHandlerFactory(rabbit.rabbit, policy, storage, mapper).create(context);
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertReject(int code, String messagePart, Executable action) {
        RejectException error = assertThrows(RejectException.class, action);
        assertEquals(code, error.getCode());
        assertTrue(error.getMessage().contains(messagePart), error.getMessage());
    }
}
