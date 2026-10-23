package ru.corvex.mailav.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ru.corvex.mailav.ClamAvClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class AntivirusCheckListenerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-23T07:15:00Z");

    private ObjectMapper mapper;
    private RawMessageClient raw;
    private ClamAvClient clam;
    private RabbitTemplate rabbit;
    private AntivirusCheckListener listener;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        raw = mock(RawMessageClient.class);
        clam = mock(ClamAvClient.class);
        rabbit = mock(RabbitTemplate.class);
        listener = new AntivirusCheckListener(mapper, raw, clam, rabbit);
    }

    @Test
    void publishesInfectedVerdictAndClosesRawStream() throws Exception {
        CloseTrackingInputStream input = arrangeScan(
                new ClamAvClient.ScanResult(ClamAvClient.Result.INFECTED, "Eicar-Test-Signature"));
        completeConfirm(true, null);

        listener.onSpamChecked(spamCheckedPayload());

        MailPipelineEvents.AvCheckedEvent published = publishedEvent();
        assertEquals("raw-9", published.rawRef());
        assertEquals("sender@example.test", published.mailFrom());
        assertEquals(List.of("employee@company.local"), published.recipients());
        assertEquals(RECEIVED_AT, published.receivedAt());
        assertTrue(published.spam());
        assertEquals(6.5, published.spamScore(), 0.001);
        assertEquals("INFECTED", published.avStatus());
        assertEquals("Eicar-Test-Signature", published.avSignature());
        assertTrue(input.closed());
    }

    @Test
    void publishesCleanAndErrorStatusesWithoutChangingSpamData() throws Exception {
        arrangeScan(new ClamAvClient.ScanResult(ClamAvClient.Result.ERROR, "clamd unavailable"));
        completeConfirm(true, null);

        listener.onSpamChecked(spamCheckedPayload());

        MailPipelineEvents.AvCheckedEvent published = publishedEvent();
        assertEquals("ERROR", published.avStatus());
        assertEquals("clamd unavailable", published.avSignature());
        assertTrue(published.spamChecked());
        assertEquals(5.0, published.spamThreshold(), 0.001);
    }

    @Test
    void rabbitNackBecomesIOException() throws Exception {
        arrangeScan(new ClamAvClient.ScanResult(ClamAvClient.Result.CLEAN, null));
        completeConfirm(false, "channel closed");

        IOException error = assertThrows(IOException.class, () -> listener.onSpamChecked(spamCheckedPayload()));

        assertTrue(error.getMessage().contains("nack"));
        assertTrue(error.getMessage().contains("channel closed"));
    }

    @Test
    void failedPublisherConfirmBecomesIOException() throws Exception {
        arrangeScan(new ClamAvClient.ScanResult(ClamAvClient.Result.CLEAN, null));
        failConfirm();

        IOException error = assertThrows(IOException.class, () -> listener.onSpamChecked(spamCheckedPayload()));

        assertTrue(error.getMessage().contains("не подтвердил публикацию"));
    }

    @Test
    void invalidPayloadDoesNotTouchPipelineDependencies() {
        assertThrows(IOException.class, () -> listener.onSpamChecked("not-json"));

        verifyNoInteractions(raw, clam, rabbit);
    }

    @Test
    void rawMessageFailureStopsBeforeScanAndPublish() throws Exception {
        when(raw.open("raw-9")).thenThrow(new IOException("spool unavailable"));

        IOException error = assertThrows(IOException.class, () -> listener.onSpamChecked(spamCheckedPayload()));

        assertEquals("spool unavailable", error.getMessage());
        verify(clam, never()).scan(any(InputStream.class));
        verifyNoInteractions(rabbit);
    }

    private CloseTrackingInputStream arrangeScan(ClamAvClient.ScanResult result) throws IOException {
        CloseTrackingInputStream input = new CloseTrackingInputStream("eml".getBytes(StandardCharsets.UTF_8));
        when(raw.open("raw-9")).thenReturn(input);
        when(clam.scan(any(InputStream.class))).thenReturn(result);
        return input;
    }

    private void completeConfirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbit).convertAndSend(
                eq(AvPipelineAmqpConfig.EXCHANGE), eq("mail.av.checked"),
                anyString(), any(CorrelationData.class));
    }

    private void failConfirm() {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().completeExceptionally(new IllegalStateException("confirm failed"));
            return null;
        }).when(rabbit).convertAndSend(
                eq(AvPipelineAmqpConfig.EXCHANGE), eq("mail.av.checked"),
                anyString(), any(CorrelationData.class));
    }

    private MailPipelineEvents.AvCheckedEvent publishedEvent() throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbit).convertAndSend(
                eq(AvPipelineAmqpConfig.EXCHANGE), eq("mail.av.checked"),
                payload.capture(), correlation.capture());
        assertEquals("raw-9:mail.av.checked", correlation.getValue().getId());
        return mapper.readValue(payload.getValue(), MailPipelineEvents.AvCheckedEvent.class);
    }

    private String spamCheckedPayload() throws Exception {
        return mapper.writeValueAsString(new MailPipelineEvents.SpamCheckedEvent(
                "raw-9",
                "sender@example.test",
                List.of("employee@company.local"),
                "198.51.100.8",
                2048,
                "def456",
                RECEIVED_AT,
                true,
                6.5,
                5.0,
                true));
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        private boolean closed() {
            return closed;
        }
    }
}
