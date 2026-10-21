package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalMailAccountRepository extends JpaRepository<ExternalMailAccount, UUID> {

    List<ExternalMailAccount> findAllByOwnerIdOrderByCreatedAtAsc(UUID ownerId);

    Optional<ExternalMailAccount> findByIdAndOwnerId(UUID id, UUID ownerId);

    boolean existsByOwnerIdAndEmailAddressIgnoreCase(UUID ownerId, String emailAddress);

    @Query("""
            select a from ExternalMailAccount a
            where a.enabled = true and a.syncEnabled = true
              and (a.nextSyncAt is null or a.nextSyncAt <= :now)
              and (a.syncLockUntil is null or a.syncLockUntil < :now)
            order by a.nextSyncAt asc nulls first
            """)
    List<ExternalMailAccount> findDue(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ExternalMailAccount a
               set a.syncLockOwner = :lockOwner, a.syncLockUntil = :lockUntil, a.updatedAt = :now
             where a.id = :id
               and a.enabled = true and a.syncEnabled = true
               and (a.syncLockUntil is null or a.syncLockUntil < :now)
            """)
    int tryClaim(@Param("id") UUID id,
                 @Param("lockOwner") String lockOwner,
                 @Param("lockUntil") Instant lockUntil,
                 @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ExternalMailAccount a
               set a.syncLockOwner = null, a.syncLockUntil = null, a.updatedAt = :now
             where a.id = :id and a.syncLockOwner = :lockOwner
            """)
    int releaseClaim(@Param("id") UUID id, @Param("lockOwner") String lockOwner, @Param("now") Instant now);
}
