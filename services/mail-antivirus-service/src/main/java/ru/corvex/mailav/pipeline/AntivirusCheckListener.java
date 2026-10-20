package ru.corvex.mailav.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ru.corvex.mailav.ClamAvClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Второе звено конвейера. ClamAV недоступен -> ScanResult ERROR: storage доставит письмо,
 * но вложения останутся заблокированы статусом ERROR до повторной проверки (fail-closed по файлам).
 */
@Component
public class AntivirusCheckListener {

    private final ObjectMapper mapper;
    private final RawMessageClient raw;
    private final ClamAvClient clam;
    private final RabbitTemplate rabbit;

    public AntivirusCheckListener(ObjectMapper mapper, RawMessageClient raw, ClamAvClient clam, RabbitTemplate rabbit) {
        this.mapper = mapper;
        this.raw = raw;
        this.clam = clam;
        this.rabbit = rabbit;
    }

    @RabbitListener(queues = AvPipelineAmqpConfig.QUEUE, containerFactory = "mailListenerFactory")
    public void onSpamChecked(String payload) throws IOException {
        MailPipelineEvents.SpamCheckedEvent event =
                mapper.readValue(payload, MailPipelineEvents.SpamCheckedEvent.class);
        ClamAvClient.ScanResult scan;
        try (InputStream in = raw.open(event.rawRef())) {
            scan = clam.scan(in);
        }
        var checked = new MailPipelineEvents.AvCheckedEvent(
                event.rawRef(), event.mailFrom(), event.recipients(), event.remoteAddress(),
                event.sizeBytes(), event.sha256(), event.receivedAt(),
                event.spam(), event.spamScore(), event.spamThreshold(), event.spamChecked(),
                scan.result().name(), scan.signature());
        publishConfirmed("mail.av.checked", checked, event.rawRef());
    }

    private void publishConfirmed(String routingKey, Object event, String correlationId) throws IOException {
        CorrelationData correlation = new CorrelationData(correlationId + ":" + routingKey);
        rabbit.convertAndSend(AvPipelineAmqpConfig.EXCHANGE, routingKey, mapper.writeValueAsString(event), correlation);
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
