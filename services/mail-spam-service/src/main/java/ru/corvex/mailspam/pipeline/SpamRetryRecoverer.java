package ru.corvex.mailspam.pipeline;

import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;

/**
 * Возвращает письмо в очередь только при недоступном mail-storage-service: это временный сбой,
 * а не «ядовитое» сообщение. Всё остальное уходит в DLQ по прежней политике.
 * <p>Так повторяется решение антивирусного звена ({@code AvRetryRecoverer}), где в очередь
 * возвращается подтверждённое отсутствие вердикта.
 */
final class SpamRetryRecoverer implements MessageRecoverer {

    private final RejectAndDontRequeueRecoverer fallback = new RejectAndDontRequeueRecoverer();

    @Override
    public void recover(Message message, Throwable cause) {
        Throwable current = cause;
        // Обход ограничен: у испорченной цепочки причин возможен цикл.
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof StorageUnavailableException) {
                throw new ImmediateRequeueAmqpException(cause);
            }
            current = current.getCause();
        }
        fallback.recover(message, cause);
    }
}
