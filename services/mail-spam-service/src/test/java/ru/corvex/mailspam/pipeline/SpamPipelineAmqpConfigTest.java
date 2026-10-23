package ru.corvex.mailspam.pipeline;

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

class SpamPipelineAmqpConfigTest {

    private final SpamPipelineAmqpConfig config = new SpamPipelineAmqpConfig();

    @Test
    void declaresDurableSpamQueueDlxAndBindings() {
        Declarables declarables = config.spamPipelineDeclarables();

        List<TopicExchange> exchanges = allOf(declarables, TopicExchange.class);
        assertEquals(List.of(SpamPipelineAmqpConfig.EXCHANGE, SpamPipelineAmqpConfig.DLX),
                exchanges.stream().map(TopicExchange::getName).sorted().toList());
        assertTrue(exchanges.stream().allMatch(TopicExchange::isDurable));

        Map<String, Queue> queues = allOf(declarables, Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, Function.identity()));
        assertEquals(2, queues.size());
        Queue queue = queues.get(SpamPipelineAmqpConfig.QUEUE);
        assertTrue(queue.isDurable());
        assertEquals(SpamPipelineAmqpConfig.DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(SpamPipelineAmqpConfig.DLQ, queue.getArguments().get("x-dead-letter-routing-key"));
        assertTrue(queues.get(SpamPipelineAmqpConfig.DLQ).isDurable());

        List<Binding> bindings = allOf(declarables, Binding.class);
        assertEquals(2, bindings.size());
        assertTrue(bindings.stream().anyMatch(binding ->
                SpamPipelineAmqpConfig.EXCHANGE.equals(binding.getExchange())
                        && SpamPipelineAmqpConfig.QUEUE.equals(binding.getDestination())
                        && SpamPipelineAmqpConfig.ROUTING_RECEIVED.equals(binding.getRoutingKey())));
        assertTrue(bindings.stream().anyMatch(binding ->
                SpamPipelineAmqpConfig.DLX.equals(binding.getExchange())
                        && SpamPipelineAmqpConfig.DLQ.equals(binding.getDestination())
                        && SpamPipelineAmqpConfig.DLQ.equals(binding.getRoutingKey())));
    }

    @Test
    void createsSimpleConverterAndListenerFactory() {
        assertTrue(config.simpleMessageConverter() instanceof SimpleMessageConverter);
        assertNotNull(config.mailListenerFactory(mock(ConnectionFactory.class)));
    }

    @Test
    void exposesStablePipelineNames() {
        assertEquals("corvex.mail", SpamPipelineAmqpConfig.EXCHANGE);
        assertEquals("corvex.mail.dlx", SpamPipelineAmqpConfig.DLX);
        assertEquals("mail.spam.inbound", SpamPipelineAmqpConfig.QUEUE);
        assertEquals("mail.spam.inbound.dlq", SpamPipelineAmqpConfig.DLQ);
        assertEquals("mail.inbound.received", SpamPipelineAmqpConfig.ROUTING_RECEIVED);
    }

    private static <T> List<T> allOf(Declarables declarables, Class<T> type) {
        return declarables.getDeclarables().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
