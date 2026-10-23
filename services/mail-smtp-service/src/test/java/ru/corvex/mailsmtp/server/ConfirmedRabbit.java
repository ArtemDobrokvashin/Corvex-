package ru.corvex.mailsmtp.server;

import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** RabbitTemplate mock для handler-ов с ручным завершением publisher confirm. */
final class ConfirmedRabbit {

    enum Mode {ACK, NACK, RETURNED, FAIL}

    record Published(String exchange, String routingKey, String payload, CorrelationData correlation) {
    }

    final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    final List<Published> published = new CopyOnWriteArrayList<>();
    volatile Mode mode = Mode.ACK;
    volatile int nackOnCall;

    ConfirmedRabbit() {
        doAnswer(invocation -> {
            String exchange = invocation.getArgument(0);
            String routingKey = invocation.getArgument(1);
            Object payload = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            published.add(new Published(exchange, routingKey, String.valueOf(payload), correlation));
            if (nackOnCall == published.size()) {
                correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
                return null;
            }
            switch (mode) {
                case ACK -> correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                case NACK -> correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker nack"));
                case RETURNED -> {
                    correlation.setReturned(mock(ReturnedMessage.class));
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                }
                case FAIL -> correlation.getFuture().completeExceptionally(new IllegalStateException("confirm failed"));
            }
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
    }
}
