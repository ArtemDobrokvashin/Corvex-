package ru.corvex.mailsmtp.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Исходящий воркер: spool → DKIM → MX по доменам. Временные отказы уходят в TTL retry-очереди
 * (5m→15m→1h→4h) до 72 часов от постановки в очередь; постоянные 5xx превращаются в DSN через
 * событие mail.outbound.bounced. Spool удаляется только когда не осталось временных получателей.
 */
@Component
public class OutboundWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboundWorker.class);
    private static final Duration MAX_QUEUE_AGE = Duration.ofHours(72);

    private final ObjectMapper mapper;
    private final StorageRawClient raw;
    private final DkimSigner dkim;
    private final SmtpRelayClient relay;
    private final RabbitTemplate rabbit;

    public OutboundWorker(ObjectMapper mapper, StorageRawClient raw, DkimSigner dkim,
                          SmtpRelayClient relay, RabbitTemplate rabbit) {
        this.mapper = mapper;
        this.raw = raw;
        this.dkim = dkim;
        this.relay = relay;
        this.rabbit = rabbit;
    }

    @RabbitListener(queues = OutboundAmqpConfig.QUEUE, containerFactory = "mailListenerFactory")
    public void onOutboundQueued(String payload) throws IOException {
        OutboundEvents.OutboundQueuedEvent event = mapper.readValue(payload, OutboundEvents.OutboundQueuedEvent.class);

        byte[] eml;
        try {
            eml = raw.download(event.rawRef());
        } catch (StorageRawClient.RawNotFoundException e) {
            for (String recipient : event.recipients()) {
                publishBounced(event, recipient, "Исходящее письмо потеряно из spool до отправки");
            }
            return;
        } catch (IOException e) {
            scheduleRetryOrBounce(event, event.recipients(), "mail-storage временно недоступен: " + e.getMessage());
            return;
        }

        byte[] signed;
        try {
            signed = dkim.signIfConfigured(eml, event.mailFrom());
        } catch (RuntimeException e) {
            scheduleRetryOrBounce(event, event.recipients(), "DKIM: " + e.getMessage());
            return;
        }

        Map<String, List<String>> byDomain = new LinkedHashMap<>();
        event.recipients().forEach(r -> byDomain.computeIfAbsent(domainOf(r), k -> new ArrayList<>()).add(r));

        List<String> sent = new ArrayList<>();
        List<String> temporary = new ArrayList<>();
        String tempDiagnostic = "Временная ошибка доставки";
        for (var entry : byDomain.entrySet()) {
            for (SmtpRelayClient.RecipientResult result : relay.sendToDomain(signed, event.mailFrom(), entry.getKey(), entry.getValue())) {
                switch (result.status()) {
                    case SENT -> sent.add(result.recipient());
                    case PERMANENT -> publishBounced(event, result.recipient(), result.diagnostic());
                    case TEMPORARY -> {
                        temporary.add(result.recipient());
                        tempDiagnostic = result.diagnostic();
                    }
                }
            }
        }

        if (!sent.isEmpty()) {
            publish(OutboundAmqpConfig.EXCHANGE, "mail.outbound.sent", new OutboundEvents.OutboundSentEvent(
                    event.rawRef(), event.mailFrom(), sent, event.messageIdHeader(), event.senderId(), Instant.now()),
                    event.rawRef(), false);
        }
        if (!temporary.isEmpty()) {
            scheduleRetryOrBounce(event, temporary, tempDiagnostic);
            return;
        }
        raw.delete(event.rawRef());
    }

    private void scheduleRetryOrBounce(OutboundEvents.OutboundQueuedEvent event, List<String> recipients,
                                       String diagnostic) throws IOException {
        Instant deadline = event.queuedAt() == null ? Instant.now() : event.queuedAt().plus(MAX_QUEUE_AGE);
        if (Instant.now().isAfter(deadline)) {
            for (String recipient : recipients) {
                publishBounced(event, recipient, "Превышен срок повторных доставок (72 часа). Последняя ошибка: " + diagnostic);
            }
            raw.delete(event.rawRef());
            return;
        }
        var retry = new OutboundEvents.OutboundQueuedEvent(
                event.rawRef(), event.mailFrom(), recipients, event.messageIdHeader(), event.senderId(),
                event.queuedAt(), event.attempt() + 1);
        String retryQueue = OutboundAmqpConfig.retryQueueForAttempt(Math.max(1, event.attempt()));
        publishToQueue(retryQueue, retry, event.rawRef());
        log.info("Outbound retry scheduled: rawRef={} attempt={} queue={} recipients={}",
                event.rawRef(), retry.attempt(), retryQueue, recipients);
    }

    private void publishBounced(OutboundEvents.OutboundQueuedEvent event, String recipient, String diagnostic) throws IOException {
        publish(OutboundAmqpConfig.EXCHANGE, "mail.outbound.bounced", new OutboundEvents.OutboundBouncedEvent(
                event.rawRef(), event.mailFrom(), recipient, diagnostic, event.messageIdHeader(),
                event.senderId(), Instant.now()), event.rawRef() + ":" + recipient, true);
    }

    private void publishToQueue(String queue, Object event, String correlationId) throws IOException {
        CorrelationData correlation = new CorrelationData(correlationId + ":" + queue);
        rabbit.convertAndSend(queue, mapper.writeValueAsString(event), correlation);
        awaitConfirm(correlation, correlationId);
    }

    private void publish(String exchange, String routingKey, Object event, String correlationId, boolean confirm) throws IOException {
        String json = mapper.writeValueAsString(event);
        if (!confirm) {
            rabbit.convertAndSend(exchange, routingKey, json);
            return;
        }
        CorrelationData correlation = new CorrelationData(correlationId + ":" + routingKey);
        rabbit.convertAndSend(exchange, routingKey, json, correlation);
        awaitConfirm(correlation, correlationId);
    }

    private void awaitConfirm(CorrelationData correlation, String correlationId) throws IOException {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IOException("RabbitMQ nack для " + correlationId + ": " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IOException("RabbitMQ вернул немаршрутизируемое событие " + correlationId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание publisher confirm прервано", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("RabbitMQ не подтвердил публикацию " + correlationId, e);
        }
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }
}
