package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.corvex.mailstorage.domain.ExternalMailAuditEvent;

import java.util.List;
import java.util.UUID;

public interface ExternalMailAuditEventRepository extends JpaRepository<ExternalMailAuditEvent, UUID> {

    List<ExternalMailAuditEvent> findTop50ByAccount_IdOrderByCreatedAtDesc(UUID accountId);
}
