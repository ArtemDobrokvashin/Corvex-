package ru.corvex.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.auth.domain.LoginAttempt;

import java.time.Instant;
import java.util.Optional;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {

    Optional<LoginAttempt> findFirstByUsernameIgnoreCaseAndSuccessTrueOrderByCreatedAtDesc(String username);

    /**
     * Неудачи для lockout: попытки с причиной LOCKED не считаются (иначе блокировка
     * продлевала бы сама себя), окно считается от последнего успеха или границы окна.
     */
    @Query("""
            select count(a) from LoginAttempt a
            where lower(a.username) = lower(:username) and a.success = false
              and (a.reason is null or a.reason <> 'LOCKED')
              and a.createdAt > :since
            """)
    long countFailuresSince(@Param("username") String username, @Param("since") Instant since);

    long countBySuccessTrueAndCreatedAtAfter(Instant since);
}
