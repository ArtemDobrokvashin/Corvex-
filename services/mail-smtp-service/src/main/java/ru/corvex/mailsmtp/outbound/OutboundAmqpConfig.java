package ru.corvex.mailsmtp.outbound;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Исходящая топология: основная очередь + TTL retry-очереди 5m/15m/1h/4h.
 * Retry-очереди не имеют потребителей: после TTL RabbitMQ dead-letter-ит событие обратно
 * в corvex.mail с ключом mail.outbound.queued. Ядовитые события основной очереди уходят в DLQ.
 */
@Configuration
public class OutboundAmqpConfig {

    public static final String EXCHANGE = "corvex.mail";
    public static final String DLX = "corvex.mail.dlx";
    public static final String QUEUE = "mail.outbound.send";
    public static final String DLQ = QUEUE + ".dlq";
    public static final String ROUTING_QUEUED = "mail.outbound.queued";

    public record RetryTier(String queue, Duration ttl) {
    }

    public static final List<RetryTier> RETRY_TIERS = List.of(
            new RetryTier("mail.outbound.retry.5m", Duration.ofMinutes(5)),
            new RetryTier("mail.outbound.retry.15m", Duration.ofMinutes(15)),
            new RetryTier("mail.outbound.retry.1h", Duration.ofHours(1)),
            new RetryTier("mail.outbound.retry.4h", Duration.ofHours(4)));

    public static String retryQueueForAttempt(int attempt) {
        int idx = Math.min(Math.max(attempt, 1) - 1, RETRY_TIERS.size() - 1);
        return RETRY_TIERS.get(idx).queue();
    }

    @Bean
    Declarables outboundDeclarables() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_QUEUED);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(DLQ);

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);
        declarables.add(dlx);
        declarables.add(queue);
        declarables.add(dlq);
        declarables.add(binding);
        declarables.add(dlqBinding);
        for (RetryTier tier : RETRY_TIERS) {
            declarables.add(QueueBuilder.durable(tier.queue())
                    .withArgument("x-message-ttl", tier.ttl().toMillis())
                    .withArgument("x-dead-letter-exchange", EXCHANGE)
                    .withArgument("x-dead-letter-routing-key", ROUTING_QUEUED)
                    .build());
        }
        return new Declarables(declarables);
    }

    @Bean
    MessageConverter simpleMessageConverter() {
        return new SimpleMessageConverter();
    }

    @Bean(name = "mailListenerFactory")
    SimpleRabbitListenerContainerFactory mailListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(mailRetryAdvice());
        return factory;
    }

    private Advice mailRetryAdvice() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1_000, 2.0, 5_000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
