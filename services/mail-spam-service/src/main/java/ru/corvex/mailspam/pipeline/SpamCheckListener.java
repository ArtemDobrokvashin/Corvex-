package ru.corvex.mailspam.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ru.corvex.mailspam.SpamAssassinClient;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Первое звено после SMTP. При недоступности spamd клиент возвращает checked=false:
 * это fail-open по архитектуре (письмо пойдёт дальше со spamScore=null), а не повод терять почту.
 */
@Component
public class SpamCheckListener {

    private final ObjectMapper mapper;
    private final RawMessageClient raw;
    private final SpamAssassinClient spam;
    private final RabbitTemplate rabbit;

    public SpamCheckListener(ObjectMapper mapper, RawMessageClient raw, SpamAssassinClient spam, RabbitTemplate rabbit) {
        this.mapper = mapper;
        this.raw = raw;
        this.spam = spam;
        this.rabbit = rabbit;
    }

    @RabbitListener(queues = SpamPipelineAmqpConfig.QUEUE, containerFactory = "mailListenerFactory")
    public void onInboundReceived(String payload) throws IOException {
        MailPipelineEvents.InboundReceivedEvent event =
                mapper.readValue(payload, MailPipelineEvents.InboundReceivedEvent.class);
        byte[] eml = raw.download(event.rawRef());
        SpamAssassinClient.Verdict verdict = spam.check(eml);
        var checked = new MailPipelineEvents.SpamCheckedEvent(
                event.rawRef(), event.mailFrom(), event.recipients(), event.remoteAddress(),
                event.sizeBytes(), event.sha256(), event.receivedAt(),
                verdict.checked() && verdict.spam(),
                verdict.checked() ? verdict.score() : null,
                verdict.threshold(),
                verdict.checked());
        publishConfirmed("mail.spam.checked", checked, event.rawRef());
    }

    private void publishConfirmed(String routingKey, Object event, String correlationId) throws IOException {
        CorrelationData correlation = new CorrelationData(correlationId + ":" + routingKey);
        rabbit.convertAndSend(SpamPipelineAmqpConfig.EXCHANGE, routingKey, mapper.writeValueAsString(event), correlation);
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
