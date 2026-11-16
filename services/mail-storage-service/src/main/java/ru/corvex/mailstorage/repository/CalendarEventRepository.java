package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.CalendarEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {

    Optional<CalendarEvent> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

    /**
     * События, видимые в диапазоне: пересекающиеся по времени плюс все повторяющиеся,
     * начавшиеся раньше конца диапазона (их вхождения вычисляет клиент или планировщик).
     */
    @Query("""
            select e from CalendarEvent e
            where e.ownerId = :ownerId and e.deletedAt is null
              and e.startsAt < :to
              and (e.endsAt > :from or e.recurrence <> 'none')
            order by e.startsAt asc
            """)
    List<CalendarEvent> findVisible(@Param("ownerId") UUID ownerId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    @Query("""
            select e from CalendarEvent e
            where e.deletedAt is null and e.reminderMinutes is not null
              and e.startsAt < :until and e.endsAt > :since
            """)
    List<CalendarEvent> findWithReminderInWindow(@Param("since") Instant since,
                                                 @Param("until") Instant until);

    /** Повторяющиеся события могут начаться задолго до окна планировщика. */
    @Query("""
            select e from CalendarEvent e
            where e.deletedAt is null and e.reminderMinutes is not null
              and e.recurrence <> 'none' and e.startsAt < :until
            """)
    List<CalendarEvent> findRecurringWithReminder(@Param("until") Instant until);
}
