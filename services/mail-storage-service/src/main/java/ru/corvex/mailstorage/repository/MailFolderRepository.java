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
}
