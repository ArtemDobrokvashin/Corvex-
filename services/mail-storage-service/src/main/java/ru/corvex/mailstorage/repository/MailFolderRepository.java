package ru.corvex.mailstorage.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.MailFolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailFolderRepository extends JpaRepository<MailFolder, UUID> {

    Optional<MailFolder> findFirstByOwnerIdAndSpecialUseIgnoreCase(UUID ownerId, String specialUse);

    Optional<MailFolder> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<MailFolder> findAllByOwnerIdOrderBySpecialUseAscNameAsc(UUID ownerId);

    /**
     * UID выделяется только под пессимистичной блокировкой строки папки: два параллельных
     * delivery-воркера не должны получить один и тот же IMAP UID.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from MailFolder f where f.id = :id")
    Optional<MailFolder> lockById(@Param("id") UUID id);

    // ---- Протокольный API этапа 3: CREATE/DELETE/RENAME папок из IMAP. ----

    boolean existsByParent_Id(UUID parentId);

    /** Уникальность имени среди соседей; parentId=null сравнивается через IS NULL явно. */
    @Query("""
            select count(f) > 0 from MailFolder f
            where f.ownerId = :ownerId
              and ((:parentId is null and f.parent is null) or f.parent.id = :parentId)
              and lower(f.name) = lower(:name)
            """)
    boolean existsSibling(@Param("ownerId") UUID ownerId,
                          @Param("parentId") UUID parentId,
                          @Param("name") String name);
}
