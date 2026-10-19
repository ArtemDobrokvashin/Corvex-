package ru.corvex.mailspam.pipeline;

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

/** Топология spam-звена: mail.inbound.received -> SpamAssassin -> mail.spam.checked. */
@Configuration
public class SpamPipelineAmqpConfig {

    public static final String EXCHANGE = "corvex.mail";
    public static final String DLX = "corvex.mail.dlx";
    public static final String QUEUE = "mail.spam.inbound";
    public static final String DLQ = QUEUE + ".dlq";
    public static final String ROUTING_RECEIVED = "mail.inbound.received";

    @Bean
    Declarables spamPipelineDeclarables() {
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DLX, true, false);
        Queue queue = QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
        Queue dlq = QueueBuilder.durable(DLQ).build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_RECEIVED);
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(DLQ);
        return new Declarables(exchange, dlx, queue, dlq, binding, dlqBinding);
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
