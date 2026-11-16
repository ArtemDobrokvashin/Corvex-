package ru.corvex.mailstorage.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.mailstorage.domain.CalendarReminderDelivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CalendarReminderRepository extends JpaRepository<CalendarReminderDelivery, Long> {

    boolean existsByEventIdAndOccurrenceAt(UUID eventId, Instant occurrenceAt);

    /**
     * Забирает созревшие напоминания под блокировку строк. Второй экземпляр
     * сервиса ждёт коммита и после него уже не видит строки со статусом PENDING,
     * поэтому одно напоминание не уходит дважды.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from CalendarReminderDelivery d
            where d.status = 'PENDING' and d.nextAttemptAt <= :now
            order by d.dueAt asc
            """)
    List<CalendarReminderDelivery> lockDue(@Param("now") Instant now, Pageable pageable);

    @Transactional
    @Modifying
    @Query("""
            update CalendarReminderDelivery d
            set d.status = 'PENDING', d.claimedAt = null, d.nextAttemptAt = :now
            where d.status = 'SENDING' and d.claimedAt < :staleBefore
            """)
    int requeueStale(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("""
            update CalendarReminderDelivery d
            set d.status = 'CANCELLED', d.lastError = :reason
            where d.eventId = :eventId and d.status in ('PENDING', 'SENDING')
            """)
    int cancelPendingForEvent(@Param("eventId") UUID eventId, @Param("reason") String reason);
}
