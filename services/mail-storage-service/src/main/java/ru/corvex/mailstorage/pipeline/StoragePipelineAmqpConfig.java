package ru.corvex.mailstorage.pipeline;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Топология storage-звена: mail.av.checked -> доставка; после 5 попыток событие уходит в DLQ. */
@Configuration
public class StoragePipelineAmqpConfig {

    public static final String EXCHANGE = "corvex.mail";
    public static final String DLX = "corvex.mail.dlx";
    public static final String QUEUE = "mail.storage.av-checked";
    public static final String DLQ = QUEUE + ".dlq";
    public static final String ROUTING_AV_CHECKED = "mail.av.checked";
    public static final String BOUNCE_QUEUE = "mail.storage.outbound-bounced";
    public static final String BOUNCE_DLQ = BOUNCE_QUEUE + ".dlq";
    public static final String ROUTING_BOUNCED = "mail.outbound.bounced";

    @Bean
    Declarables storagePipelineDeclarables() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        Queue bounceQueue = QueueBuilder.durable(BOUNCE_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", BOUNCE_DLQ)
                .build();
        Queue bounceDlq = QueueBuilder.durable(BOUNCE_DLQ).build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_AV_CHECKED);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(DLQ);
        Binding bounceBinding = BindingBuilder.bind(bounceQueue).to(exchange).with(ROUTING_BOUNCED);
        Binding bounceDlqBinding = BindingBuilder.bind(bounceDlq).to(dlx).with(BOUNCE_DLQ);
        return new Declarables(exchange, dlx, queue, dlq, bounceQueue, bounceDlq,
                binding, dlqBinding, bounceBinding, bounceDlqBinding);
    }

    @Bean
    MessageConverter simpleMessageConverter() {
        return new SimpleMessageConverter();
    }

    @Bean(name = "mailListenerFactory")
    SimpleRabbitListenerContainerFactory mailListenerFactory(ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // Конвейер сознательно обменивается JSON-строками без Java __TypeId__: у каждого сервиса своя record-копия контракта.
        factory.setMessageConverter(new SimpleMessageConverter());
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(mailRetryAdvice());
        return factory;
    }

    private Advice mailRetryAdvice() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(5)
                .backOffOptions(1_000, 2.0, 10_000)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
