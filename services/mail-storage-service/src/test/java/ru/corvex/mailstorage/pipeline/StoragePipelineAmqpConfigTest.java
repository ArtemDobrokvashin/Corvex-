package ru.corvex.mailstorage.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StoragePipelineAmqpConfigTest {

    private final StoragePipelineAmqpConfig config = new StoragePipelineAmqpConfig();

    @Test
    void declaresDeliveryAndBounceQueuesWithDeadLetters() {
        Declarables declarables = config.storagePipelineDeclarables();

        List<TopicExchange> exchanges = allOf(declarables, TopicExchange.class);
        assertEquals(List.of(StoragePipelineAmqpConfig.EXCHANGE, StoragePipelineAmqpConfig.DLX),
                exchanges.stream().map(TopicExchange::getName).sorted().toList());
        assertTrue(exchanges.stream().allMatch(TopicExchange::isDurable));
        assertTrue(exchanges.stream().noneMatch(TopicExchange::isAutoDelete));

        Map<String, Queue> queues = allOf(declarables, Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, Function.identity()));
        assertEquals(4, queues.size());
        assertDeadLetter(queues.get(StoragePipelineAmqpConfig.QUEUE), StoragePipelineAmqpConfig.DLQ);
        assertDeadLetter(queues.get(StoragePipelineAmqpConfig.BOUNCE_QUEUE), StoragePipelineAmqpConfig.BOUNCE_DLQ);
        assertTrue(queues.get(StoragePipelineAmqpConfig.DLQ).isDurable());
        assertTrue(queues.get(StoragePipelineAmqpConfig.BOUNCE_DLQ).isDurable());

        List<Binding> bindings = allOf(declarables, Binding.class);
        assertEquals(4, bindings.size());
        assertTrue(hasBinding(bindings, StoragePipelineAmqpConfig.EXCHANGE,
                StoragePipelineAmqpConfig.QUEUE, StoragePipelineAmqpConfig.ROUTING_AV_CHECKED));
        assertTrue(hasBinding(bindings, StoragePipelineAmqpConfig.DLX,
                StoragePipelineAmqpConfig.DLQ, StoragePipelineAmqpConfig.DLQ));
        assertTrue(hasBinding(bindings, StoragePipelineAmqpConfig.EXCHANGE,
                StoragePipelineAmqpConfig.BOUNCE_QUEUE, StoragePipelineAmqpConfig.ROUTING_BOUNCED));
        assertTrue(hasBinding(bindings, StoragePipelineAmqpConfig.DLX,
                StoragePipelineAmqpConfig.BOUNCE_DLQ, StoragePipelineAmqpConfig.BOUNCE_DLQ));
    }

    @Test
    void createsSimpleConverterAndNonRequeueingListenerFactory() {
        assertInstanceOf(SimpleMessageConverter.class, config.simpleMessageConverter());
        var factory = config.mailListenerFactory(mock(ConnectionFactory.class));
        assertNotNull(factory);
    }

    @Test
    void pipelineEventRecordsRoundTripThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant at = Instant.parse("2026-10-23T10:00:00Z");
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID folder = UUID.randomUUID();

        assertRoundTrip(mapper, new MailPipelineEvents.AvCheckedEvent(
                "raw", "a@x", List.of("b@y"), "smtp", 12, "hash", at, true, 8.5, 5.0, true, "CLEAN", null),
                MailPipelineEvents.AvCheckedEvent.class);
        assertRoundTrip(mapper, new MailPipelineEvents.DeliveredEvent("raw", id, owner, "b@y", folder, 7, at),
                MailPipelineEvents.DeliveredEvent.class);
        assertRoundTrip(mapper, new MailPipelineEvents.InboundReceivedEvent(
                "raw", "a@x", List.of("b@y"), "webmail", 12, "hash", at),
                MailPipelineEvents.InboundReceivedEvent.class);
        assertRoundTrip(mapper, new MailPipelineEvents.OutboundQueuedEvent(
                "raw", "a@x", List.of("b@y"), "<m@x>", owner, at, 1),
                MailPipelineEvents.OutboundQueuedEvent.class);
        assertRoundTrip(mapper, new MailPipelineEvents.ExternalOutboundQueuedEvent(
                "raw", id, "a@x", List.of("b@y"), "<m@x>", owner, at, 2),
                MailPipelineEvents.ExternalOutboundQueuedEvent.class);
        assertRoundTrip(mapper, new MailPipelineEvents.OutboundBouncedEvent(
                "raw", "a@x", "b@y", "550", "<m@x>", owner, at),
                MailPipelineEvents.OutboundBouncedEvent.class);
    }

    private static <T> void assertRoundTrip(ObjectMapper mapper, T value, Class<T> type) throws Exception {
        assertEquals(value, mapper.readValue(mapper.writeValueAsString(value), type));
    }

    private static void assertDeadLetter(Queue queue, String dlq) {
        assertNotNull(queue);
        assertTrue(queue.isDurable());
        assertEquals(StoragePipelineAmqpConfig.DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(dlq, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    private static boolean hasBinding(List<Binding> bindings, String exchange, String destination, String key) {
        return bindings.stream().anyMatch(binding -> exchange.equals(binding.getExchange())
                && destination.equals(binding.getDestination())
                && key.equals(binding.getRoutingKey()));
    }

    private static <T> List<T> allOf(Declarables declarables, Class<T> type) {
        return declarables.getDeclarables().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
