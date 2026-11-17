package ru.corvex.mailstorage.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.ExternalMailOAuthState;

import java.time.Instant;
import java.util.Optional;

public interface ExternalMailOAuthStateRepository extends JpaRepository<ExternalMailOAuthState, String> {

    /**
     * Блокировка строки обязательна: callback может прийти дважды (повторная доставка,
     * двойной клик), и второй запрос не должен обменять тот же код ещё раз.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ExternalMailOAuthState s where s.stateHash = :stateHash")
    Optional<ExternalMailOAuthState> findForUpdate(@Param("stateHash") String stateHash);

    @Modifying
    @Query("delete from ExternalMailOAuthState s where s.expiresAt < :before")
    int deleteExpired(@Param("before") Instant before);
}
