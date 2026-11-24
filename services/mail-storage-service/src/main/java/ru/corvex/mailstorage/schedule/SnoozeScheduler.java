package ru.corvex.mailstorage.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.corvex.mailstorage.domain.SnoozedMessage;
import ru.corvex.mailstorage.repository.SnoozedMessageRepository;

import java.time.Instant;
import java.util.List;

/**
 * Возврат «уснувших» писем.
 *
 * <p>Отдельного события для клиента нет: письмо остаётся в папке всё время,
 * поэтому достаточно удалить строку — и оно снова попадёт в следующий список.
 * Браузер замечает это при обычном обновлении папки или по WS-событию о новом
 * письме; отдельная рассылка здесь была бы лишней связностью.
 */
@Component
public class SnoozeScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnoozeScheduler.class);
    static final int BATCH = 200;

    private final SnoozedMessageRepository snoozed;

    @Value("${corvex.mail.snooze-enabled:true}")
    private boolean enabled = true;

    public SnoozeScheduler(SnoozedMessageRepository snoozed) {
        this.snoozed = snoozed;
    }

    @Scheduled(fixedDelayString = "${corvex.mail.snooze-delay-ms:60000}")
    public void wakeDue() {
        if (!enabled) {
            return;
        }
        List<SnoozedMessage> due = snoozed.findDue(Instant.now(), PageRequest.of(0, BATCH));
        if (due.isEmpty()) {
            return;
        }
        // Каждое письмо возвращается отдельно: одна битая строка не должна
        // оставить остальные «спящими» навсегда.
        for (SnoozedMessage row : due) {
            try {
                snoozed.deleteById(row.getMessageId());
            } catch (RuntimeException e) {
                log.warn("Не удалось вернуть письмо {} из snooze: {}", row.getMessageId(), e.getMessage());
            }
        }
    }
}
