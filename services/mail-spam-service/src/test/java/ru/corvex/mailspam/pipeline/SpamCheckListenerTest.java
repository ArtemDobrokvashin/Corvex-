package ru.corvex.mailspam.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ru.corvex.mailspam.SpamAssassinClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpamCheckListenerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-23T07:00:00Z");

    private ObjectMapper mapper;
    private RawMessageClient raw;
    private SpamAssassinClient spam;
    private RabbitTemplate rabbit;
    private SpamCheckListener listener;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        raw = mock(RawMessageClient.class);
        spam = mock(SpamAssassinClient.class);
        rabbit = mock(RabbitTemplate.class);
        listener = new SpamCheckListener(mapper, raw, spam, rabbit);
    }

    @Test
    void publishesCheckedSpamEventAfterPublisherAck() throws Exception {
        arrangeScan(new SpamAssassinClient.Verdict(true, 8.25, 5.0, true));
        completeConfirm(true, null);

        listener.onInboundReceived(inboundPayload());

        MailPipelineEvents.SpamCheckedEvent published = publishedEvent();
        assertEquals("raw-1", published.rawRef());
        assertEquals("sender@example.test", published.mailFrom());
        assertEquals(List.of("employee@company.local"), published.recipients());
        assertEquals(RECEIVED_AT, published.receivedAt());
        assertTrue(published.spam());
        assertEquals(8.25, published.spamScore(), 0.001);
        assertEquals(5.0, published.spamThreshold(), 0.001);
        assertTrue(published.spamChecked());
    }

    @Test
    void uncheckedVerdictPublishesNullScoreAndDoesNotMarkSpam() throws Exception {
        arrangeScan(new SpamAssassinClient.Verdict(true, 99, 5.0, false));
        completeConfirm(true, null);

        listener.onInboundReceived(inboundPayload());

        MailPipelineEvents.SpamCheckedEvent published = publishedEvent();
        assertFalse(published.spam());
        assertNull(published.spamScore());
        assertFalse(published.spamChecked());
    }

    @Test
    void rabbitNackBecomesIOException() throws Exception {
        arrangeScan(new SpamAssassinClient.Verdict(false, 0.5, 5.0, true));
        completeConfirm(false, "broker rejected");

        IOException error = assertThrows(IOException.class, () -> listener.onInboundReceived(inboundPayload()));

        assertTrue(error.getMessage().contains("nack"));
        assertTrue(error.getMessage().contains("broker rejected"));
    }

    @Test
    void failedPublisherConfirmBecomesIOException() throws Exception {
        arrangeScan(new SpamAssassinClient.Verdict(false, 0.5, 5.0, true));
        failConfirm();

        IOException error = assertThrows(IOException.class, () -> listener.onInboundReceived(inboundPayload()));

        assertTrue(error.getMessage().contains("не подтвердил публикацию"));
    }

    @Test
    void invalidPayloadDoesNotTouchPipelineDependencies() {
        assertThrows(IOException.class, () -> listener.onInboundReceived("{broken-json"));

        verifyNoInteractions(raw, spam, rabbit);
    }

    @Test
    void rawMessageFailureStopsBeforeSpamCheckAndPublish() throws Exception {
        when(raw.download("raw-1")).thenThrow(new IOException("spool unavailable"));

        IOException error = assertThrows(IOException.class, () -> listener.onInboundReceived(inboundPayload()));

        assertEquals("spool unavailable", error.getMessage());
        verify(spam, never()).check(any(byte[].class));
        verifyNoInteractions(rabbit);
    }

    private void arrangeScan(SpamAssassinClient.Verdict verdict) throws IOException {
        when(raw.download("raw-1")).thenReturn("eml".getBytes(StandardCharsets.UTF_8));
        when(spam.check(any(byte[].class))).thenReturn(verdict);
    }

    private void completeConfirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbit).convertAndSend(
                eq(SpamPipelineAmqpConfig.EXCHANGE), eq("mail.spam.checked"),
                anyString(), any(CorrelationData.class));
    }

    private void failConfirm() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().completeExceptionally(new IllegalStateException("confirm failed"));
            return null;
        }).when(rabbit).convertAndSend(
                eq(SpamPipelineAmqpConfig.EXCHANGE), eq("mail.spam.checked"),
                anyString(), any(CorrelationData.class));
    }

    private MailPipelineEvents.SpamCheckedEvent publishedEvent() throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(
                eq(SpamPipelineAmqpConfig.EXCHANGE), eq("mail.spam.checked"),
                payload.capture(), correlation.capture());
        assertEquals("raw-1:mail.spam.checked", correlation.getValue().getId());
        return mapper.readValue(payload.getValue(), MailPipelineEvents.SpamCheckedEvent.class);
    }

    private String inboundPayload() throws Exception {
        return mapper.writeValueAsString(new MailPipelineEvents.InboundReceivedEvent(
                "raw-1",
                "sender@example.test",
                List.of("employee@company.local"),
                "192.0.2.10",
                1234,
                "abc123",
                RECEIVED_AT));
    }
}
