package ru.corvex.mailstorage.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.CalendarEvent;
import ru.corvex.mailstorage.domain.CalendarReminderDelivery;
import ru.corvex.mailstorage.repository.CalendarEventRepository;
import ru.corvex.mailstorage.repository.CalendarReminderRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Серверные напоминания: работают, даже если браузер закрыт.
 *
 * <p>Цикл: спланировать созревающие напоминания → заблокировать их строки →
 * отправить через notification-service → отметить SENT или запланировать повтор.
 * Один сбой (битое событие, недоступный SMTP) не останавливает остальные.
 */
@Component
public class CalendarReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderScheduler.class);
    /**
     * Не отправляем напоминание, если встреча началась больше двух часов назад:
     * после долгого простоя сервера старые напоминания — шум, а не польза.
     */
    static final Duration GRACE = Duration.ofHours(2);
    /** Просроченная отправка возвращается в очередь: процесс мог упасть между письмом и отметкой. */
    static final Duration CLAIM_STALE = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    static final int BATCH = 25;

    private final CalendarEventRepository events;
    private final CalendarReminderRepository reminders;
    private final CalendarReminderNotifier notifier;
    private final AuthUserClient users;
    /**
     * Планирование и захват строк идут в явных транзакциях: вызов
     * @Transactional-метода изнутри бина прокси не проходит, а блокировка
     * PESSIMISTIC_WRITE без активной транзакции невозможна.
     */
    private final TransactionTemplate transaction;
    /**
     * Каждая строка очереди создаётся в своей транзакции: гонка двух экземпляров
     * упирается в уникальный индекс, и откат затрагивает только эту строку, а не
     * весь план напоминаний.
     */
    private final TransactionTemplate insertTransaction;

    @Value("${corvex.calendar.reminders-enabled:true}")
    private boolean enabled = true;

    public CalendarReminderScheduler(CalendarEventRepository events,
                                     CalendarReminderRepository reminders,
                                     CalendarReminderNotifier notifier,
                                     AuthUserClient users,
                                     PlatformTransactionManager transactions) {
        this.events = events;
        this.reminders = reminders;
        this.notifier = notifier;
        this.users = users;
        this.transaction = new TransactionTemplate(transactions);
        this.insertTransaction = new TransactionTemplate(transactions);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${corvex.calendar.reminder-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        Instant now = Instant.now();
        try {
            plan(now);
        } catch (RuntimeException failure) {
            log.warn("Calendar reminder planning failed: {}", failure.getMessage());
        }
        try {
            int requeued = reminders.requeueStale(now.minus(CLAIM_STALE), now);
            if (requeued > 0) {
                log.warn("Requeued {} stale calendar reminder(s) after a crash", requeued);
            }
        } catch (RuntimeException failure) {
            log.warn("Calendar reminder requeue failed: {}", failure.getMessage());
        }
        List<CalendarReminderDelivery> claimed;
        try {
            claimed = transaction.execute(status -> claim(now));
        } catch (RuntimeException failure) {
            log.warn("Calendar reminder claim failed: {}", failure.getMessage());
            return;
        }
        for (CalendarReminderDelivery delivery : claimed) {
            try {
                deliver(delivery, now);
            } catch (RuntimeException failure) {
                // Строка останется в SENDING и вернётся в очередь через CLAIM_STALE:
                // один сбой (например, auth-service недоступен) не должен
                // останавливать остальные напоминания.
                log.warn("Calendar reminder {} delivery failed: {}", delivery.getId(), failure.getMessage());
            }
        }
    }

    /**
     * Создаёт строки доставки для наступивших напоминаний. Окно покрывает
     * максимальный интервал напоминания, поэтому недельное напоминание тоже
     * попадает в план, а не только минутное.
     */
    void plan(Instant now) {
        Instant until = now.plus(Duration.ofDays(8));
        // Повторяющееся событие попадает в оба запроса; дедупликация по id
        // избавляет от лишних проверок существования строк.
        Map<UUID, CalendarEvent> unique = new LinkedHashMap<>();
        events.findWithReminderInWindow(now.minus(GRACE), until).forEach(event -> unique.put(event.getId(), event));
        events.findRecurringWithReminder(until).forEach(event -> unique.put(event.getId(), event));
        List<CalendarEvent> candidates = new ArrayList<>(unique.values());

        for (CalendarEvent event : candidates) {
            if (event.getReminderMinutes() == null) {
                continue;
            }
            List<Instant> starts;
            try {
                starts = CalendarRecurrence.occurrences(event.getRecurrence(), event.getStartsAt(),
                        event.getTimeZone(), now.minus(GRACE), until);
            } catch (RuntimeException invalid) {
                log.warn("Calendar event {} has an unreadable recurrence/zone", event.getId());
                continue;
            }
            for (Instant occurrence : starts) {
                if (!occurrence.isAfter(now.minus(GRACE))) {
                    continue;
                }
                Instant due = occurrence.minus(Duration.ofMinutes(event.getReminderMinutes()));
                // Строку создаём только к моменту отправки: иначе очередь раздувается
                // вхождениями на неделю вперёд, а отменённое событие оставит мусор.
                if (due.isAfter(now.plus(Duration.ofMinutes(1)))) {
                    continue;
                }
                if (reminders.existsByEventIdAndOccurrenceAt(event.getId(), occurrence)) {
                    continue;
                }
                CalendarReminderDelivery delivery = new CalendarReminderDelivery();
                delivery.setEventId(event.getId());
                delivery.setOwnerId(event.getOwnerId());
                delivery.setOccurrenceAt(occurrence);
                delivery.setDueAt(due);
                delivery.setNextAttemptAt(due.isBefore(now) ? now : due);
                try {
                    insertTransaction.executeWithoutResult(status -> reminders.saveAndFlush(delivery));
                } catch (RuntimeException concurrent) {
                    // Уникальный индекс (event_id, occurrence_at) — второй экземпляр
                    // уже создал строку; это ожидаемая гонка, а не ошибка.
                    log.debug("Calendar reminder row already created by another instance");
                }
            }
        }
    }

    /** @return заблокированные строки, уже помеченные SENDING; каждая попытка учтена. */
    List<CalendarReminderDelivery> claim(Instant now) {
        List<CalendarReminderDelivery> due = reminders.lockDue(now, PageRequest.of(0, BATCH));
        for (CalendarReminderDelivery delivery : due) {
            delivery.setStatus(CalendarReminderDelivery.Status.SENDING.name());
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setClaimedAt(now);
        }
        return reminders.saveAll(due);
    }

    void deliver(CalendarReminderDelivery delivery, Instant now) {
        Optional<CalendarEvent> event = events.findById(delivery.getEventId())
                .filter(item -> item.getDeletedAt() == null);
        if (event.isEmpty()) {
            finish(delivery, CalendarReminderDelivery.Status.CANCELLED, "Событие удалено", now);
            return;
        }
        CalendarEvent calendarEvent = event.get();
        Optional<AuthUserClient.MailboxOwner> owner = users.findById(calendarEvent.getOwnerId());
        if (owner.isEmpty() || !owner.get().active() || owner.get().email() == null) {
            finish(delivery, CalendarReminderDelivery.Status.FAILED, "Владелец события недоступен", now);
            return;
        }
        try {
            notifier.send(new CalendarReminderNotifier.Reminder(
                    owner.get().email(),
                    calendarEvent.getTitle(),
                    calendarEvent.getStartsAt().toString(),
                    calendarEvent.getTimeZone(),
                    calendarEvent.getLocation(),
                    attendeeCount(calendarEvent),
                    calendarEvent.isAllDay()));
            finish(delivery, CalendarReminderDelivery.Status.SENT, null, now);
        } catch (Exception failure) {
            String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            if (delivery.getAttempts() >= MAX_ATTEMPTS) {
                finish(delivery, CalendarReminderDelivery.Status.FAILED, reason, now);
            } else {
                delivery.setStatus(CalendarReminderDelivery.Status.PENDING.name());
                delivery.setClaimedAt(null);
                delivery.setLastError(truncate(reason));
                delivery.setNextAttemptAt(now.plus(backoff(delivery.getAttempts())));
                reminders.saveAndFlush(delivery);
            }
        }
    }

    private void finish(CalendarReminderDelivery delivery, CalendarReminderDelivery.Status status,
                        String error, Instant now) {
        delivery.setStatus(status.name());
        delivery.setLastError(truncate(error));
        delivery.setClaimedAt(null);
        if (status == CalendarReminderDelivery.Status.SENT) {
            delivery.setSentAt(now);
        }
        reminders.saveAndFlush(delivery);
    }

    static int attendeeCount(CalendarEvent event) {
        String attendees = event.getAttendees();
        if (attendees == null || attendees.isBlank()) {
            return 0;
        }
        return attendees.split("\n", -1).length;
    }

    /** 1, 2, 4, 8 минут: серия сбоев не должна превращаться в шквал запросов. */
    static Duration backoff(int attempts) {
        long minutes = Math.min(8, 1L << Math.min(3, Math.max(0, attempts - 1)));
        return Duration.ofMinutes(minutes);
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
