package ru.corvex.mailstorage.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.corvex.mailstorage.domain.ExternalMailImportedMessage;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ExternalMailImportedMessageRepository extends JpaRepository<ExternalMailImportedMessage, UUID> {

    @Query("""
            select m from ExternalMailImportedMessage m
            where m.account.id = :accountId
              and m.remoteFolder = :remoteFolder
              and m.remoteUid = :remoteUid
              and ((m.UIDValidity is null and :UIDValidity is null) or m.UIDValidity = :UIDValidity)
            """)
    Optional<ExternalMailImportedMessage> findRemote(@Param("accountId") UUID accountId,
                                                     @Param("remoteFolder") String remoteFolder,
                                                     @Param("UIDValidity") Long UIDValidity,
                                                     @Param("remoteUid") String remoteUid);

    boolean existsByAccount_IdAndRemoteFolderAndSha256AndStatusIn(UUID accountId,
                                                                  String remoteFolder,
                                                                  String sha256,
                                                                  Collection<ExternalMailImportedMessage.Status> statuses);

    boolean existsByAccount_IdAndRemoteFolderAndMessageIdHeaderIgnoreCaseAndStatusIn(
            UUID accountId,
            String remoteFolder,
            String messageIdHeader,
            Collection<ExternalMailImportedMessage.Status> statuses);

    Optional<ExternalMailImportedMessage> findFirstByRawRef(String rawRef);

    long countByAccount_IdAndStatus(UUID accountId, ExternalMailImportedMessage.Status status);

    void deleteAllByAccount_Id(UUID accountId);
}
