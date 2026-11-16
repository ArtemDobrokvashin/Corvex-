package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.MailMigrationFolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MailMigrationFolderRepository extends JpaRepository<MailMigrationFolder, UUID> {

    List<MailMigrationFolder> findByJob_IdOrderByRemoteFolderAsc(UUID jobId);

    Optional<MailMigrationFolder> findByJob_IdAndRemoteFolder(UUID jobId, String remoteFolder);
}
