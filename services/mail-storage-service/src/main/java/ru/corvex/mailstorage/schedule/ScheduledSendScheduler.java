package ru.corvex.mailstorage.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Тик отложенной отправки. Отдельный компонент, а не метод в
 * {@link OutboxService}: @Scheduled на том же бине шёл бы через прокси и
 * транзакция обнимала бы весь пакет — одно зависшее письмо держало бы остальные.
 */
@Component
public class ScheduledSendScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledSendScheduler.class);
    static final int BATCH = 20;

    private final OutboxService outbox;

    @Value("${corvex.mail.outbox-enabled:true}")
    private boolean enabled = true;

    public ScheduledSendScheduler(OutboxService outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${corvex.mail.outbox-delay-ms:30000}")
    public void dispatchDue() {
        if (!enabled) {
            return;
        }
        for (UUID id : outbox.dueIds(Instant.now(), BATCH)) {
            try {
                outbox.dispatch(id);
            } catch (RuntimeException e) {
                // Одно письмо с битой строкой не должно останавливать очередь.
                log.warn("Не удалось обработать отложенное письмо {}: {}", id, e.getMessage());
            }
        }
    }
}
