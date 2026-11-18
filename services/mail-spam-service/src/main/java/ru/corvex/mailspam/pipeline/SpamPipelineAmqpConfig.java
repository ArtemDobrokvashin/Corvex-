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

    static final int RETRY_MAX_ATTEMPTS = 6;
    static final long RETRY_INITIAL_MS = 2_000;
    static final long RETRY_MAX_MS = 20_000;

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

    /**
     * Окно ретраев должно переживать перезапуск mail-storage-service: 2+4+8+16+20 ≈ 50 секунд.
     * Раньше было 5 попыток с суммой ≈ 15 секунд, и рестарт хранилища на полминуты отправлял
     * пачку писем в DLQ, откуда они уже не возвращаются: курсор синхронизации к тому моменту сдвинут.
     */
    private Advice mailRetryAdvice() {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(RETRY_MAX_ATTEMPTS)
                .backOffOptions(RETRY_INITIAL_MS, 2.0, RETRY_MAX_MS)
                .recoverer(new SpamRetryRecoverer())
                .build();
    }
}
