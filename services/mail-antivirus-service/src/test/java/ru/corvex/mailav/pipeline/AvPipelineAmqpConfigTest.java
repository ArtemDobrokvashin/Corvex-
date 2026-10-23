package ru.corvex.mailav.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AvPipelineAmqpConfigTest {

    private final AvPipelineAmqpConfig config = new AvPipelineAmqpConfig();

    @Test
    void declaresDurableAvQueueDlxAndBindings() {
        Declarables declarables = config.avPipelineDeclarables();

        List<TopicExchange> exchanges = allOf(declarables, TopicExchange.class);
        assertEquals(List.of(AvPipelineAmqpConfig.EXCHANGE, AvPipelineAmqpConfig.DLX),
                exchanges.stream().map(TopicExchange::getName).sorted().toList());
        assertTrue(exchanges.stream().allMatch(TopicExchange::isDurable));

        Map<String, Queue> queues = allOf(declarables, Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, Function.identity()));
        assertEquals(2, queues.size());
        Queue queue = queues.get(AvPipelineAmqpConfig.QUEUE);
        assertTrue(queue.isDurable());
        assertEquals(AvPipelineAmqpConfig.DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(AvPipelineAmqpConfig.DLQ, queue.getArguments().get("x-dead-letter-routing-key"));
        assertTrue(queues.get(AvPipelineAmqpConfig.DLQ).isDurable());

        List<Binding> bindings = allOf(declarables, Binding.class);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(binding ->
                AvPipelineAmqpConfig.EXCHANGE.equals(binding.getExchange())
                        && AvPipelineAmqpConfig.QUEUE.equals(binding.getDestination())
                        && AvPipelineAmqpConfig.ROUTING_SPAM_CHECKED.equals(binding.getRoutingKey())));
        assertTrue(bindings.stream().anyMatch(binding ->
                AvPipelineAmqpConfig.DLX.equals(binding.getExchange())
                        && AvPipelineAmqpConfig.DLQ.equals(binding.getDestination())
                        && AvPipelineAmqpConfig.DLQ.equals(binding.getRoutingKey())));
    }

    @Test
    void createsSimpleConverterAndListenerFactory() {
        assertTrue(config.simpleMessageConverter() instanceof SimpleMessageConverter);
        assertNotNull(config.mailListenerFactory(mock(ConnectionFactory.class)));
    }

    @Test
    void exposesStablePipelineNames() {
        assertEquals("corvex.mail", AvPipelineAmqpConfig.EXCHANGE);
        assertEquals("corvex.mail.dlx", AvPipelineAmqpConfig.DLX);
        assertEquals("mail.av.spam-checked", AvPipelineAmqpConfig.QUEUE);
        assertEquals("mail.av.spam-checked.dlq", AvPipelineAmqpConfig.DLQ);
        assertEquals("mail.spam.checked", AvPipelineAmqpConfig.ROUTING_SPAM_CHECKED);
    }

    private static <T> List<T> allOf(Declarables declarables, Class<T> type) {
        return declarables.getDeclarables().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
