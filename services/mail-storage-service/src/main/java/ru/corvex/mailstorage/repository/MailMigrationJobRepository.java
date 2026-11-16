package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.MailMigrationJob;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailMigrationJobRepository extends JpaRepository<MailMigrationJob, UUID> {

    List<MailMigrationJob> findTop20ByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<MailMigrationJob> findFirstByOwnerIdAndStatusIn(UUID ownerId,
                                                            Collection<MailMigrationJob.Status> statuses);

    Optional<MailMigrationJob> findFirstByAccount_IdAndStatusIn(UUID accountId,
                                                               Collection<MailMigrationJob.Status> statuses);

    List<MailMigrationJob> findByOwnerIdAndStatusIn(UUID ownerId, Collection<MailMigrationJob.Status> statuses);
}
