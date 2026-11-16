package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.MailMigrationInvitation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailMigrationInvitationRepository extends JpaRepository<MailMigrationInvitation, UUID> {

    Optional<MailMigrationInvitation> findFirstByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    Optional<MailMigrationInvitation> findByOwnerIdAndEmailIgnoreCase(UUID ownerId, String email);

    List<MailMigrationInvitation> findAllByOwnerIdInOrderByCreatedAtDesc(List<UUID> ownerIds);

    long countByStatus(MailMigrationInvitation.Status status);
}
