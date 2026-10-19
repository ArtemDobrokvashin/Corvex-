package ru.corvex.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.auth.domain.RefreshSession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    Optional<RefreshSession> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("""
            update RefreshSession s set s.revokedAt = :now
            where s.userId = :userId and s.revokedAt is null and s.tokenHash <> :keepTokenHash
            """)
    int revokeAllExcept(@Param("userId") UUID userId,
                        @Param("keepTokenHash") String keepTokenHash,
                        @Param("now") Instant now);
}
