package ru.corvex.mailstorage.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Публикация событий storage с ожиданием broker confirm: отправка письма не считается принятой в очередь без ACK. */
@Component
public class MailEventPublisher {

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;

    public MailEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper) {
        this.rabbit = rabbit;
        this.mapper = mapper;
    }

    public void publishConfirmed(String routingKey, Object event, String correlationId) {
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать почтовое событие", e);
        }
        CorrelationData correlation = new CorrelationData(correlationId + ":" + routingKey);
        rabbit.convertAndSend(StoragePipelineAmqpConfig.EXCHANGE, routingKey, json, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new IllegalStateException("RabbitMQ nack для " + correlationId + ": " + confirm.getReason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ вернул немаршрутизируемое событие " + correlationId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание publisher confirm прервано", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("RabbitMQ не подтвердил публикацию " + correlationId, e);
        }
    }
}
