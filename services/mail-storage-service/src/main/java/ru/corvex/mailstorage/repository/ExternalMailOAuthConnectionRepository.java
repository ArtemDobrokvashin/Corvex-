package ru.corvex.mailstorage.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.ExternalMailOAuthConnection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ExternalMailOAuthConnectionRepository
        extends JpaRepository<ExternalMailOAuthConnection, UUID> {

    /** Незавершённое подключение: владелец совпадает и токены ещё не забрал мастер. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ExternalMailOAuthConnection c "
            + "where c.id = :id and c.ownerId = :ownerId and c.consumedAt is null")
    Optional<ExternalMailOAuthConnection> findOpenForUpdate(@Param("id") UUID id,
                                                           @Param("ownerId") UUID ownerId);

    @Modifying
    @Query("delete from ExternalMailOAuthConnection c where c.createdAt < :before and c.consumedAt is not null")
    int deleteConsumedBefore(@Param("before") Instant before);
}
