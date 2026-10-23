package ru.corvex.mailsmtp.outbound;

import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** RabbitTemplate mock, который записывает публикации и синхронно завершает publisher confirms. */
final class RabbitPublishes {

    enum Mode {ACK, NACK, RETURNED, FAIL}

    record Published(String first, String second, String payload, CorrelationData correlation) {
    }

    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final List<Published> confirmed = new CopyOnWriteArrayList<>();
    final List<Published> unconfirmed = new CopyOnWriteArrayList<>();
    volatile Mode mode = Mode.ACK;

    RabbitPublishes() {
        doAnswer(invocation -> {
            String exchange = invocation.getArgument(0);
            String routingKey = invocation.getArgument(1);
            Object payload = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            confirmed.add(new Published(exchange, routingKey, String.valueOf(payload), correlation));
            complete(correlation);
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));

        doAnswer(invocation -> {
            String queue = invocation.getArgument(0);
            Object payload = invocation.getArgument(1);
            CorrelationData correlation = invocation.getArgument(2);
            confirmed.add(new Published(queue, null, String.valueOf(payload), correlation));
            complete(correlation);
            return null;
        }).when(rabbit).convertAndSend(anyString(), any(Object.class), any(CorrelationData.class));

        doAnswer(invocation -> {
            String exchange = invocation.getArgument(0);
            String routingKey = invocation.getArgument(1);
            Object payload = invocation.getArgument(2);
            unconfirmed.add(new Published(exchange, routingKey, String.valueOf(payload), null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString());
    }

    List<Published> confirmedTo(String first, String second) {
        return confirmed.stream()
                .filter(p -> first.equals(p.first()) && (second == null ? p.second() == null : second.equals(p.second())))
                .toList();
    }

    private void complete(CorrelationData correlation) {
        switch (mode) {
            case ACK -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            case NACK -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
            case RETURNED -> {
                correlation.setReturned(mock(ReturnedMessage.class));
                correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            }
            case FAIL -> correlation.getFuture().completeExceptionally(new IllegalStateException("confirm failed"));
        }
    }
}
