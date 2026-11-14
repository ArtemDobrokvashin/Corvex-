package ru.corvex.mailav.pipeline;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

/**
 * Requeue only a confirmed no-verdict outcome: the listener has not published a
 * checked event in that case. All other failures retain the existing DLQ policy.
 * This is not a durable journal, backpressure system or crash-recovery proof.
 */
final class AvRetryRecoverer implements MessageRecoverer {
    private final RejectAndDontRequeueRecoverer fallback = new RejectAndDontRequeueRecoverer();

    @Override
    public void recover(Message message, Throwable cause) {
        Throwable current = cause;
        // Bound traversal even if a malformed exception has a cyclic cause chain.
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof AvVerdictUnavailableException) {
                throw new ImmediateRequeueAmqpException(cause);
            }
            current = current.getCause();
        }
        fallback.recover(message, cause);
    }
}
