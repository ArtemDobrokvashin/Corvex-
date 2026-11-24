package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.SnoozedMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SnoozedMessageRepository extends JpaRepository<SnoozedMessage, UUID> {

    Optional<SnoozedMessage> findByMessageIdAndOwnerId(UUID messageId, UUID ownerId);

    /** Список папки: скрываем письма, срок которых ещё не наступил. */
    @Query("select s.messageId from SnoozedMessage s where s.ownerId = :ownerId and s.wakeAt > :now")
    List<UUID> findActiveMessageIds(@Param("ownerId") UUID ownerId, @Param("now") Instant now);

    @Query("select s from SnoozedMessage s where s.wakeAt <= :now order by s.wakeAt")
    List<SnoozedMessage> findDue(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("delete from SnoozedMessage s where s.messageId = :messageId and s.ownerId = :ownerId")
    int deleteOwned(@Param("messageId") UUID messageId, @Param("ownerId") UUID ownerId);

    void deleteByMessageId(UUID messageId);
}
