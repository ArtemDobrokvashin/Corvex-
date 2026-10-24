package ru.corvex.mailstorage.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ru.corvex.mailstorage.mime.MimeMailParser;
import ru.corvex.mailstorage.search.MessageSearchIndexer;
import ru.corvex.mailstorage.service.MailDeliveryService;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvCheckedListenerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private MailDeliveryService delivery;
    private MessageSearchIndexer indexer;
    private RabbitTemplate rabbit;
    private RawMessageSpool spool;
    private AvCheckedListener listener;

    @BeforeEach
    void setUp() {
        delivery = mock(MailDeliveryService.class);
        indexer = mock(MessageSearchIndexer.class);
        rabbit = mock(RabbitTemplate.class);
        spool = mock(RawMessageSpool.class);
        listener = new AvCheckedListener(mapper, delivery, indexer, rabbit, spool);
    }

    @Test
    void deliversEachRecipientIndexesAndPublishesDeliveredOnlyForStoredCopies() throws Exception {
        MailPipelineEvents.AvCheckedEvent event = event(List.of("one@company.local", "two@company.local"));
        MailDeliveryService.DeliveryResult result = result(event.rawRef());
        when(delivery.deliver(event, "one@company.local")).thenReturn(Optional.of(result));
        when(delivery.deliver(event, "two@company.local")).thenReturn(Optional.empty());

        listener.onAvChecked(mapper.writeValueAsString(event));

        verify(indexer).index(result);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(eq(StoragePipelineAmqpConfig.EXCHANGE), eq("mail.delivered"), payload.capture());
        MailPipelineEvents.DeliveredEvent delivered = mapper.readValue(
                String.valueOf(payload.getValue()), MailPipelineEvents.DeliveredEvent.class);
        assertEquals(event.rawRef(), delivered.rawRef());
        assertEquals(result.messageId(), delivered.messageId());
        assertEquals(result.uid(), delivered.uid());
        verify(spool).delete(event.rawRef());
    }

    @Test
    void spoolDeleteFailureDoesNotTurnSuccessfulDeliveryIntoRedelivery() throws Exception {
        MailPipelineEvents.AvCheckedEvent event = event(List.of("one@company.local"));
        when(delivery.deliver(event, "one@company.local")).thenReturn(Optional.empty());
        doThrow(new IOException("still locked")).when(spool).delete(event.rawRef());

        listener.onAvChecked(mapper.writeValueAsString(event));

        verify(spool).delete(event.rawRef());
    }

    @Test
    void deliveredPublicationFailureIsLoggedAndSpoolStillDeleted() throws Exception {
        MailPipelineEvents.AvCheckedEvent event = event(List.of("one@company.local"));
        when(delivery.deliver(event, "one@company.local")).thenReturn(Optional.of(result(event.rawRef())));
        doThrow(new IllegalStateException("broker down"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        listener.onAvChecked(mapper.writeValueAsString(event));

        verify(indexer).index(any());
        verify(spool).delete(event.rawRef());
    }

    @Test
    void deliveryFailurePropagatesBeforeSpoolDeletion() throws Exception {
        MailPipelineEvents.AvCheckedEvent event = event(List.of("one@company.local"));
        when(delivery.deliver(event, "one@company.local")).thenThrow(new IOException("maildir unavailable"));

        assertThrows(IOException.class, () -> listener.onAvChecked(mapper.writeValueAsString(event)));

        verify(spool, never()).delete(anyString());
        verify(indexer, never()).index(any());
    }

    @Test
    void malformedPayloadIsRejected() {
        assertThrows(JsonProcessingException.class, () -> listener.onAvChecked("{not-json"));
    }

    private MailPipelineEvents.AvCheckedEvent event(List<String> recipients) {
        return new MailPipelineEvents.AvCheckedEvent(
                UUID.randomUUID().toString(), "sender@partner.test", recipients, "198.51.100.2",
                123, "a".repeat(64), Instant.parse("2026-01-01T00:00:00Z"),
                false, 0.1, 5.0, true, "CLEAN", null);
    }

    private MailDeliveryService.DeliveryResult result(String rawRef) {
        MimeMailParser.ParsedMail parsed = new MimeMailParser.ParsedMail(
                "<m@x>", "sender@partner.test", "subject", "snippet", "body", null,
                Instant.parse("2026-01-01T00:00:00Z"), List.of(), List.of(), List.of());
        return new MailDeliveryService.DeliveryResult(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 17, "one@company.local", parsed);
    }
}
