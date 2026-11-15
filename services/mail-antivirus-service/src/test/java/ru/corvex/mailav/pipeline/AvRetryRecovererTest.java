package ru.corvex.mailav.pipeline;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Decision logic only: no listener container, RabbitMQ or ClamAV. */
class AvRetryRecovererTest {
    private final AvRetryRecoverer recoverer = new AvRetryRecoverer();
    private final Message message = new Message(new byte[0], new MessageProperties());

    @Test
    void scannerUnknownRequeuesEvenWhenWrapped() {
        assertThrows(ImmediateRequeueAmqpException.class,
                () -> recoverer.recover(message, new AvVerdictUnavailableException()));
        assertThrows(ImmediateRequeueAmqpException.class,
                () -> recoverer.recover(message, new IllegalStateException(new AvVerdictUnavailableException())));
    }

    @Test
    void scanLimitIsRejectedRatherThanRequeued() {
        ListenerExecutionFailedException failure = assertThrows(ListenerExecutionFailedException.class,
                () -> recoverer.recover(message, new AvScanLimitExceededException()));
        assertInstanceOf(AmqpRejectAndDontRequeueException.class, failure.getCause());
    }

    @Test
    void otherFailuresKeepPriorDeadLetterPolicy() {
        ListenerExecutionFailedException failure = assertThrows(ListenerExecutionFailedException.class,
                () -> recoverer.recover(message, new IOException("spool or publisher failure")));
        assertInstanceOf(AmqpRejectAndDontRequeueException.class, failure.getCause());
    }
}
