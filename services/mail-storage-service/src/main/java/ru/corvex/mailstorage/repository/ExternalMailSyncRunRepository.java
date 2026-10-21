package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.ExternalMailSyncRun;

import java.util.List;
import java.util.UUID;

public interface ExternalMailSyncRunRepository extends JpaRepository<ExternalMailSyncRun, UUID> {

    List<ExternalMailSyncRun> findTop20ByAccount_IdOrderByStartedAtDesc(UUID accountId);
}
