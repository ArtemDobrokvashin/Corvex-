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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Send-as worker: spool → authenticated SMTP провайдера (без DKIM Corvex — подписывает провайдер)
 * → Sent APPEND на IMAP провайдера. Временные ошибки идут в отдельные TTL retry-очереди до 72 часов,
 * постоянные превращаются в DSN локальному отправителю через mail.outbound.bounced.
 */
@Component
public class ExternalOutboundWorker {

    private static final Logger log = LoggerFactory.getLogger(ExternalOutboundWorker.class);
    private static final Duration MAX_QUEUE_AGE = Duration.ofHours(72);

    private final ObjectMapper mapper;
    private final StorageRawClient raw;
    private final ExternalAccountConfigClient configs;
    private final ExternalSmtpClient smtp;
    private final RabbitTemplate rabbit;

    public ExternalOutboundWorker(ObjectMapper mapper, StorageRawClient raw,
                                  ExternalAccountConfigClient configs, ExternalSmtpClient smtp,
                                  RabbitTemplate rabbit) {
        this.mapper = mapper;
        this.raw = raw;
        this.configs = configs;
        this.smtp = smtp;
        this.rabbit = rabbit;
    }

    @RabbitListener(queues = OutboundAmqpConfig.EXTERNAL_QUEUE, containerFactory = "mailListenerFactory")
    public void onExternalQueued(String payload) throws IOException {
        OutboundEvents.ExternalOutboundQueuedEvent event =
                mapper.readValue(payload, OutboundEvents.ExternalOutboundQueuedEvent.class);

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

        ExternalAccountConfigClient.TransportConfig config;
        try {
            config = configs.fetch(event.externalAccountId());
        } catch (ExternalAccountConfigClient.ConfigException e) {
            if (e.permanent()) {
                for (String recipient : event.recipients()) publishBounced(event, recipient, e.getMessage());
                raw.delete(event.rawRef());
            } else {
                scheduleRetryOrBounce(event, event.recipients(), e.getMessage());
            }
            return;
        } catch (IOException e) {
            scheduleRetryOrBounce(event, event.recipients(), "Не удалось получить конфигурацию внешнего ящика: " + e.getMessage());
            return;
        }

        List<String> sent = new ArrayList<>();
        List<String> temporary = new ArrayList<>();
        String tempDiagnostic = "Временная ошибка SMTP провайдера";
        for (SmtpRelayClient.RecipientResult result : smtp.send(config, eml, event.mailFrom(), event.recipients())) {
            switch (result.status()) {
                case SENT -> sent.add(result.recipient());
                case PERMANENT -> publishBounced(event, result.recipient(), result.diagnostic());
                case TEMPORARY -> {
                    temporary.add(result.recipient());
                    tempDiagnostic = result.diagnostic();
                }
            }
        }

        if (!sent.isEmpty()) {
            publish(OutboundAmqpConfig.EXCHANGE, "mail.outbound.sent", new OutboundEvents.OutboundSentEvent(
                    event.rawRef(), event.mailFrom(), sent, event.messageIdHeader(), event.senderId(), Instant.now()),
                    event.rawRef(), false);
            smtp.appendSent(config, eml);
        }
        if (!temporary.isEmpty()) {
            scheduleRetryOrBounce(event, temporary, tempDiagnostic);
            return;
        }
        raw.delete(event.rawRef());
        log.info("External send-as completed rawRef={} account={} recipients={}",
                event.rawRef(), event.externalAccountId(), sent.size());
    }

    private void scheduleRetryOrBounce(OutboundEvents.ExternalOutboundQueuedEvent event, List<String> recipients,
                                       String diagnostic) throws IOException {
        Instant deadline = event.queuedAt() == null ? Instant.now() : event.queuedAt().plus(MAX_QUEUE_AGE);
        if (Instant.now().isAfter(deadline)) {
            for (String recipient : recipients) {
                publishBounced(event, recipient, "Превышен срок повторных доставок (72 часа). Последняя ошибка: " + diagnostic);
            }
            raw.delete(event.rawRef());
            return;
        }
        var retry = new OutboundEvents.ExternalOutboundQueuedEvent(
                event.rawRef(), event.externalAccountId(), event.mailFrom(), recipients, event.messageIdHeader(),
                event.senderId(), event.queuedAt(), event.attempt() + 1);
        String retryQueue = OutboundAmqpConfig.externalRetryQueueForAttempt(Math.max(1, event.attempt()));
        publishToQueue(retryQueue, retry, event.rawRef());
        log.info("External outbound retry scheduled: rawRef={} attempt={} queue={} recipients={}",
                event.rawRef(), retry.attempt(), retryQueue, recipients);
    }

    private void publishBounced(OutboundEvents.ExternalOutboundQueuedEvent event, String recipient,
                                String diagnostic) throws IOException {
        publish(OutboundAmqpConfig.EXCHANGE, "mail.outbound.bounced", new OutboundEvents.OutboundBouncedEvent(
                event.rawRef(), event.mailFrom(), recipient, diagnostic, event.messageIdHeader(),
                event.senderId(), Instant.now()), event.rawRef() + ":" + recipient, true);
    }

    private void publishToQueue(String queue, Object event, String correlationId) throws IOException {
        CorrelationData correlation = new CorrelationData(correlationId + ":" + queue);
        rabbit.convertAndSend(queue, mapper.writeValueAsString(event), correlation);
        awaitConfirm(correlation, correlationId);
    }

    private void publish(String exchange, String routingKey, Object event, String correlationId,
                         boolean confirm) throws IOException {
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
}
