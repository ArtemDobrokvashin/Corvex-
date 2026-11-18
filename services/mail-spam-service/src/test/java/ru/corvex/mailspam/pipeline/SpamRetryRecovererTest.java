package ru.corvex.mailspam.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Только логика решения: без контейнера слушателя, RabbitMQ и ClamAV. */
class SpamRetryRecovererTest {

    private final SpamRetryRecoverer recoverer = new SpamRetryRecoverer();
    private final Message message = new Message(new byte[0], new MessageProperties());

    @Test
    void requeuesWhenMailStorageIsUnavailable() {
        Throwable cause = new RuntimeException("listener failed",
                new StorageUnavailableException("mail-storage недоступен", new IOException("Connection refused")));

        assertThrows(ImmediateRequeueAmqpException.class, () -> recoverer.recover(message, cause));
    }

    @Test
    void missingSpoolGoesToDeadLetterQueue() {
        // 404 значит «файла spool нет»: повтор не поможет, сообщение должно уйти в DLQ.
        Throwable missingSpool = new RuntimeException("listener failed",
                new IOException("mail-storage вернул HTTP 404 для rawRef 42"));

        ListenerExecutionFailedException failure = assertThrows(ListenerExecutionFailedException.class,
                () -> recoverer.recover(message, missingSpool));
        assertInstanceOf(AmqpRejectAndDontRequeueException.class, failure.getCause());
    }

    @Test
    void survivesACyclicCauseChain() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        ListenerExecutionFailedException failure = assertThrows(ListenerExecutionFailedException.class,
                () -> recoverer.recover(message, first));
        assertInstanceOf(AmqpRejectAndDontRequeueException.class, failure.getCause());
    }
}
