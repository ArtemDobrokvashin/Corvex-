package ru.corvex.mailspam.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SpamRetryRecoverer.class);

    private final RejectAndDontRequeueRecoverer fallback = new RejectAndDontRequeueRecoverer();

    @Override
    public void recover(Message message, Throwable cause) {
        Throwable current = cause;
        // Обход ограничен: у испорченной цепочки причин возможен цикл.
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current instanceof StorageUnavailableException) {
                log.warn("mail-storage недоступен, письмо возвращено в очередь: {}", current.getMessage());
                throw new ImmediateRequeueAmqpException(cause);
            }
            current = current.getCause();
        }
        fallback.recover(message, cause);
    }
}
