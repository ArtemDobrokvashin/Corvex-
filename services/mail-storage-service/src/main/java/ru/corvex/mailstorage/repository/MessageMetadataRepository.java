package ru.corvex.mailstorage.repository;

import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.MessageMetadata;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageMetadataRepository extends JpaRepository<MessageMetadata, UUID> {

    boolean existsByRawRefAndOwnerId(String rawRef, UUID ownerId);

    Optional<MessageMetadata> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MessageMetadata m where m.id = :id and m.ownerId = :ownerId")
    Optional<MessageMetadata> lockOwned(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MessageMetadata m where m.folder.id = :folderId and m.uid = :uid and m.ownerId = :ownerId")
    Optional<MessageMetadata> lockFolderUid(@Param("folderId") UUID folderId, @Param("uid") long uid, @Param("ownerId") UUID ownerId);

    long countByFolder_Id(UUID folderId);

    long countByFolder_IdAndSeenFalse(UUID folderId);

    @Query("select coalesce(sum(m.sizeBytes), 0) from MessageMetadata m where m.ownerId = :ownerId")
    long sumSizeByOwnerId(@Param("ownerId") UUID ownerId);

    /**
     * Курсор по IMAP UID вместо OFFSET: глубокая прокрутка папки не деградирует.
     */
    @Query("""
            select m from MessageMetadata m
            join fetch m.folder f
            where f.id = :folderId and m.ownerId = :ownerId and m.uid < :cursorUid
            order by m.uid desc
            """)
    List<MessageMetadata> listFolderBeforeUid(@Param("folderId") UUID folderId,
                                              @Param("ownerId") UUID ownerId,
                                              @Param("cursorUid") long cursorUid,
                                              Pageable pageable);

    // ---- Протокольный API этапа 3 (IMAP/POP3): доступ строго по (owner, folder, uid). ----

    Optional<MessageMetadata> findByFolder_IdAndUidAndOwnerId(UUID folderId, long uid, UUID ownerId);

    /** Восходящий список UID для IMAP FETCH/SEARCH и POP3 LIST: протоколы работают в порядке UID. */
    List<MessageMetadata> findByFolder_IdAndOwnerIdAndUidGreaterThanOrderByUidAsc(
            UUID folderId, UUID ownerId, long afterUid, Pageable pageable);

    List<MessageMetadata> findAllByFolder_IdAndOwnerIdAndDeletedTrue(UUID folderId, UUID ownerId);

    // Select scalar UIDs so the persistence context does not retain stale entities before locking.
    @Query("select m.uid from MessageMetadata m where m.folder.id = :folderId and m.ownerId = :ownerId order by m.uid")
    List<Long> findFolderUids(@Param("folderId") UUID folderId, @Param("ownerId") UUID ownerId);

    @Query("select m.uid from MessageMetadata m where m.folder.id = :folderId and m.ownerId = :ownerId and m.deleted = true order by m.uid")
    List<Long> findDeletedUids(@Param("folderId") UUID folderId, @Param("ownerId") UUID ownerId);

    List<MessageMetadata> findAllByFolder_IdAndOwnerId(UUID folderId, UUID ownerId);

    long countByOwnerId(UUID ownerId);

    @Query("""
            select m.ownerId as ownerId, sum(m.sizeBytes) as usedBytes, count(m) as messageCount
            from MessageMetadata m
            where m.ownerId in :ownerIds
            group by m.ownerId
            """)
    List<MailboxUsageProjection> summarizeByOwnerIds(@Param("ownerIds") Collection<UUID> ownerIds);

    interface MailboxUsageProjection {
        UUID getOwnerId();
        long getUsedBytes();
        long getMessageCount();
    }
}
