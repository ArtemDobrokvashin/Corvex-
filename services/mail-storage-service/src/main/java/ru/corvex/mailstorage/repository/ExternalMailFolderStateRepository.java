package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.ExternalMailFolderState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalMailFolderStateRepository extends JpaRepository<ExternalMailFolderState, UUID> {

    Optional<ExternalMailFolderState> findByAccount_IdAndRemoteFolder(UUID accountId, String remoteFolder);

    List<ExternalMailFolderState> findAllByAccount_Id(UUID accountId);

    void deleteAllByAccount_Id(UUID accountId);
}
