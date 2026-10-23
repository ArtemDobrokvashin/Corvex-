package ru.corvex.mailstorage.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MailEventPublisherTest {

    private RabbitTemplate rabbit;
    private MailEventPublisher publisher;

    @BeforeEach
    void setUp() {
        rabbit = mock(RabbitTemplate.class);
        publisher = new MailEventPublisher(rabbit, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void publishesJsonAndAcceptsBrokerAck() throws Exception {
        completeWith(true, null, false, false);
        Map<String, Object> event = Map.of("id", "abc", "count", 2);

        publisher.publishConfirmed("mail.inbound.received", event, "corr-1");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(eq(StoragePipelineAmqpConfig.EXCHANGE),
                eq("mail.inbound.received"), payload.capture(), correlation.capture());
        assertEquals(event, new ObjectMapper().readValue(String.valueOf(payload.getValue()), Map.class));
        assertEquals("corr-1:mail.inbound.received", correlation.getValue().getId());
    }

    @Test
    void rejectsBrokerNack() {
        completeWith(false, "queue unavailable", false, false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publishConfirmed("mail.outbound.queued", Map.of("x", 1), "corr-2"));
        assertTrue(error.getMessage().contains("queue unavailable"));
    }

    @Test
    void rejectsReturnedUnroutableMessageEvenWithAck() {
        completeWith(true, null, true, false);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publishConfirmed("mail.outbound.queued", Map.of("x", 1), "corr-3"));
        assertTrue(error.getMessage().contains("немаршрутизируемое"));
    }

    @Test
    void rejectsExceptionallyFailedConfirm() {
        completeWith(true, null, false, true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> publisher.publishConfirmed("mail.outbound.queued", Map.of("x", 1), "corr-4"));
        assertTrue(error.getMessage().contains("не подтвердил"));
    }

    @Test
    void interruptedConfirmWaitRestoresInterruptFlag() {
        doAnswer(inv -> null).when(rabbit)
                .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
        Thread.currentThread().interrupt();
        try {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> publisher.publishConfirmed("mail.test", Map.of("x", 1), "corr-5"));
            assertTrue(error.getMessage().contains("прервано"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void serializationAndTransportFailuresAreVisible() {
        Map<Object, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        IllegalStateException serialization = assertThrows(IllegalStateException.class,
                () -> publisher.publishConfirmed("mail.test", cyclic, "corr-6"));
        assertTrue(serialization.getMessage().contains("сериализовать"));

        doThrow(new IllegalStateException("connection closed")).when(rabbit)
                .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
        assertThrows(IllegalStateException.class,
                () -> publisher.publishConfirmed("mail.test", Map.of("x", 1), "corr-7"));
    }

    private void completeWith(boolean ack, String reason, boolean returned, boolean fail) {
        doAnswer(inv -> {
            CorrelationData correlation = inv.getArgument(3);
            if (fail) {
                correlation.getFuture().completeExceptionally(new IllegalStateException("confirm failed"));
            } else {
                if (returned) correlation.setReturned(mock(ReturnedMessage.class));
                correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            }
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
    }
}
