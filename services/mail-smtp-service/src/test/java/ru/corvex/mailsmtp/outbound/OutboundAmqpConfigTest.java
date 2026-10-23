package ru.corvex.mailsmtp.outbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OutboundAmqpConfigTest {

    private final OutboundAmqpConfig config = new OutboundAmqpConfig();

    @Test
    void declaresMainExternalDlqAndAllRetryQueues() {
        Declarables declarables = config.outboundDeclarables();

        List<TopicExchange> exchanges = allOf(declarables, TopicExchange.class);
        assertEquals(List.of(OutboundAmqpConfig.EXCHANGE, OutboundAmqpConfig.DLX),
                exchanges.stream().map(TopicExchange::getName).sorted().toList());
        assertTrue(exchanges.stream().allMatch(TopicExchange::isDurable));
        assertTrue(exchanges.stream().noneMatch(TopicExchange::isAutoDelete));

        Map<String, Queue> queues = allOf(declarables, Queue.class).stream()
                .collect(Collectors.toMap(Queue::getName, Function.identity()));
        assertEquals(12, queues.size());
        assertDeadLetter(queues.get(OutboundAmqpConfig.QUEUE), OutboundAmqpConfig.DLQ);
        assertDeadLetter(queues.get(OutboundAmqpConfig.EXTERNAL_QUEUE), OutboundAmqpConfig.EXTERNAL_DLQ);
        assertTrue(queues.get(OutboundAmqpConfig.DLQ).isDurable());
        assertTrue(queues.get(OutboundAmqpConfig.EXTERNAL_DLQ).isDurable());

        for (OutboundAmqpConfig.RetryTier tier : OutboundAmqpConfig.RETRY_TIERS) {
            assertRetryQueue(queues.get(tier.queue()), tier, OutboundAmqpConfig.ROUTING_QUEUED);
        }
        for (OutboundAmqpConfig.RetryTier tier : OutboundAmqpConfig.EXTERNAL_RETRY_TIERS) {
            assertRetryQueue(queues.get(tier.queue()), tier, OutboundAmqpConfig.ROUTING_EXTERNAL_QUEUED);
        }

        List<Binding> bindings = allOf(declarables, Binding.class);
        assertEquals(4, bindings.size());
        assertTrue(hasBinding(bindings, OutboundAmqpConfig.EXCHANGE,
                OutboundAmqpConfig.QUEUE, OutboundAmqpConfig.ROUTING_QUEUED));
        assertTrue(hasBinding(bindings, OutboundAmqpConfig.DLX,
                OutboundAmqpConfig.DLQ, OutboundAmqpConfig.DLQ));
        assertTrue(hasBinding(bindings, OutboundAmqpConfig.EXCHANGE,
                OutboundAmqpConfig.EXTERNAL_QUEUE, OutboundAmqpConfig.ROUTING_EXTERNAL_QUEUED));
        assertTrue(hasBinding(bindings, OutboundAmqpConfig.DLX,
                OutboundAmqpConfig.EXTERNAL_DLQ, OutboundAmqpConfig.EXTERNAL_DLQ));
    }

    @Test
    void clampsRetryAttemptsToDeclaredTiers() {
        assertEquals("mail.outbound.retry.5m", OutboundAmqpConfig.retryQueueForAttempt(-10));
        assertEquals("mail.outbound.retry.5m", OutboundAmqpConfig.retryQueueForAttempt(1));
        assertEquals("mail.outbound.retry.15m", OutboundAmqpConfig.retryQueueForAttempt(2));
        assertEquals("mail.outbound.retry.1h", OutboundAmqpConfig.retryQueueForAttempt(3));
        assertEquals("mail.outbound.retry.4h", OutboundAmqpConfig.retryQueueForAttempt(4));
        assertEquals("mail.outbound.retry.4h", OutboundAmqpConfig.retryQueueForAttempt(99));

        assertEquals("mail.outbound.external.retry.5m", OutboundAmqpConfig.externalRetryQueueForAttempt(0));
        assertEquals("mail.outbound.external.retry.15m", OutboundAmqpConfig.externalRetryQueueForAttempt(2));
        assertEquals("mail.outbound.external.retry.1h", OutboundAmqpConfig.externalRetryQueueForAttempt(3));
        assertEquals("mail.outbound.external.retry.4h", OutboundAmqpConfig.externalRetryQueueForAttempt(100));
        assertEquals(List.of(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(4)),
                OutboundAmqpConfig.RETRY_TIERS.stream().map(OutboundAmqpConfig.RetryTier::ttl).toList());
    }

    @Test
    void createsConverterAndListenerFactory() {
        assertTrue(config.simpleMessageConverter() instanceof SimpleMessageConverter);
        assertNotNull(config.mailListenerFactory(mock(ConnectionFactory.class)));
    }

    @Test
    void eventRecordsKeepJsonContractsAndValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        UUID sender = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        Instant queuedAt = Instant.parse("2026-10-23T08:15:30Z");

        OutboundEvents.OutboundQueuedEvent queued = new OutboundEvents.OutboundQueuedEvent(
                "raw-1", "alice@company.local", List.of("bob@example.org"), "<m1@company.local>", sender, queuedAt, 2);
        assertEquals(queued, mapper.readValue(mapper.writeValueAsString(queued), OutboundEvents.OutboundQueuedEvent.class));

        OutboundEvents.ExternalOutboundQueuedEvent external = new OutboundEvents.ExternalOutboundQueuedEvent(
                "raw-2", account, "old@example.ru", List.of("client@example.com"), "<m2@example.ru>", sender, queuedAt, 3);
        assertEquals(external, mapper.readValue(
                mapper.writeValueAsString(external), OutboundEvents.ExternalOutboundQueuedEvent.class));

        OutboundEvents.OutboundSentEvent sent = new OutboundEvents.OutboundSentEvent(
                "raw-1", "alice@company.local", List.of("bob@example.org"), "<m1@company.local>", sender, queuedAt);
        OutboundEvents.OutboundBouncedEvent bounced = new OutboundEvents.OutboundBouncedEvent(
                "raw-1", "alice@company.local", "bob@example.org", "550 no such user", "<m1@company.local>", sender, queuedAt);
        assertEquals(sent, mapper.readValue(mapper.writeValueAsString(sent), OutboundEvents.OutboundSentEvent.class));
        assertEquals(bounced, mapper.readValue(mapper.writeValueAsString(bounced), OutboundEvents.OutboundBouncedEvent.class));
    }

    private static void assertDeadLetter(Queue queue, String dlq) {
        assertNotNull(queue);
        assertTrue(queue.isDurable());
        assertEquals(OutboundAmqpConfig.DLX, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(dlq, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    private static void assertRetryQueue(Queue queue, OutboundAmqpConfig.RetryTier tier, String routingKey) {
        assertNotNull(queue, tier.queue());
        assertTrue(queue.isDurable());
        assertEquals(tier.ttl().toMillis(), queue.getArguments().get("x-message-ttl"));
        assertEquals(OutboundAmqpConfig.EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(routingKey, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    private static boolean hasBinding(List<Binding> bindings, String exchange, String destination, String routingKey) {
        return bindings.stream().anyMatch(binding -> exchange.equals(binding.getExchange())
                && destination.equals(binding.getDestination())
                && routingKey.equals(binding.getRoutingKey()));
    }

    private static <T> List<T> allOf(Declarables declarables, Class<T> type) {
        return declarables.getDeclarables().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }
}
