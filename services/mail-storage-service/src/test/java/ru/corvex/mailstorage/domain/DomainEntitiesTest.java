package ru.corvex.mailstorage.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainEntitiesTest {

    @Test
    void externalAccountDefaultsAccessorsAndLifecycle() {
        ExternalMailAccount account = new ExternalMailAccount();
        assertEquals(ExternalMailAccount.OwnerKind.PERSONAL, account.getOwnerKind());
        assertEquals(ExternalMailAccount.Provider.CUSTOM, account.getProvider());
        assertEquals(ExternalMailAccount.AuthType.APP_PASSWORD, account.getAuthType());
        assertEquals(ExternalMailAccount.Protocol.IMAP, account.getIncomingProtocol());
        assertEquals(ExternalMailAccount.Security.SSL_TLS, account.getIncomingSecurity());
        assertTrue(account.isSmtpEnabled() && account.isEnabled() && account.isSyncEnabled() && account.isSendEnabled());
        assertEquals(ExternalMailAccount.InitialSyncMode.NEW_ONLY, account.getInitialSyncMode());
        assertEquals(ExternalMailAccount.SyncScope.INBOX_ONLY, account.getSyncScope());
        assertEquals(300, account.getPollIntervalSeconds());
        assertTrue(account.isLeaveOnServer() && account.isSaveSentOnServer());

        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        MailFolder target = new MailFolder();
        Instant at = Instant.parse("2026-01-02T03:04:05Z");
        account.setId(id);
        account.setOwnerId(owner);
        account.setCreatedBy(creator);
        account.setOwnerKind(ExternalMailAccount.OwnerKind.SHARED_MAILBOX);
        account.setLabel("Shared");
        account.setEmailAddress("shared@mail.test");
        account.setDisplayName("Shared Team");
        account.setProvider(ExternalMailAccount.Provider.GMAIL);
        account.setAuthType(ExternalMailAccount.AuthType.PASSWORD);
        account.setIncomingProtocol(ExternalMailAccount.Protocol.POP3);
        account.setIncomingHost("pop.gmail.com");
        account.setIncomingPort(995);
        account.setIncomingSecurity(ExternalMailAccount.Security.STARTTLS);
        account.setIncomingUsername("shared");
        account.setSecretEnc("encrypted");
        account.setSmtpEnabled(false);
        account.setSmtpHost("smtp.gmail.com");
        account.setSmtpPort(465);
        account.setSmtpSecurity(ExternalMailAccount.Security.PLAIN);
        account.setSmtpUsername("smtp-user");
        account.setTargetFolder(target);
        account.setEnabled(false);
        account.setSyncEnabled(false);
        account.setSendEnabled(false);
        account.setInitialSyncMode(ExternalMailAccount.InitialSyncMode.IMPORT_ALL);
        account.setSyncScope(ExternalMailAccount.SyncScope.ALL_FOLDERS);
        account.setPollIntervalSeconds(60);
        account.setLeaveOnServer(false);
        account.setMarkSeenOnServer(true);
        account.setDeleteAfterFetch(true);
        account.setSaveSentOnServer(false);
        account.setSentFolderName("Sent Items");
        account.setLastSyncAt(at);
        account.setLastSuccessAt(at);
        account.setNextSyncAt(at);
        account.setLastError("error");
        account.setConsecutiveFailures(4);
        account.setSyncLockOwner("worker");
        account.setSyncLockUntil(at);

        assertEquals(id, account.getId());
        assertEquals(owner, account.getOwnerId());
        assertEquals(creator, account.getCreatedBy());
        assertEquals("shared@mail.test", account.getEmailAddress());
        assertEquals("smtp-user", account.getSmtpUsername());
        assertEquals(target, account.getTargetFolder());
        assertFalse(account.isEnabled());
        assertTrue(account.isMarkSeenOnServer() && account.isDeleteAfterFetch());
        assertEquals(4, account.getConsecutiveFailures());

        account.prePersist();
        Instant created = account.getCreatedAt();
        Instant updated = account.getUpdatedAt();
        assertNotNull(created);
        assertNotNull(updated);
        account.prePersist();
        assertEquals(created, account.getCreatedAt(), "createdAt не перезаписывается");
        account.setUpdatedAt(at);
        account.preUpdate();
        assertTrue(account.getUpdatedAt().isAfter(at));
    }

    @Test
    void folderAndMessageAccessorsAndDefaults() {
        MailFolder parent = new MailFolder();
        MailFolder folder = new MailFolder();
        assertEquals(1, folder.getUidNext());
        assertEquals(1, folder.getUidValidity());
        assertEquals(1, folder.getHighestModseq());
        UUID folderId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        folder.setId(folderId);
        folder.setOwnerId(owner);
        folder.setParent(parent);
        folder.setName("Child");
        folder.setSpecialUse("ARCHIVE");
        folder.setUidNext(20);
        folder.setUidValidity(30);
        folder.setHighestModseq(40);
        assertEquals(folderId, folder.getId());
        assertEquals(parent, folder.getParent());
        assertEquals(40, folder.getHighestModseq());

        MessageMetadata message = new MessageMetadata();
        assertEquals(1, message.getModseq());
        assertEquals(MessageMetadata.AvStatus.PENDING, message.getAvStatus());
        UUID messageId = UUID.randomUUID();
        Instant date = Instant.parse("2026-01-01T00:00:00Z");
        message.setId(messageId);
        message.setOwnerId(owner);
        message.setFolder(folder);
        message.setUid(7);
        message.setModseq(8);
        message.setMessageIdHeader("<m@x>");
        message.setRawRef("raw");
        message.setSender("sender@x");
        message.setSubject("subject");
        message.setSnippet("snippet");
        message.setInternalDate(date);
        message.setSizeBytes(123);
        message.setMaildirPath("path");
        message.setSeen(true);
        message.setAnswered(true);
        message.setFlagged(true);
        message.setDraft(true);
        message.setDeleted(true);
        message.setSpamScore(4.5);
        message.setAvStatus(MessageMetadata.AvStatus.INFECTED);

        assertEquals(messageId, message.getId());
        assertEquals(folder, message.getFolder());
        assertEquals(7, message.getUid());
        assertEquals("<m@x>", message.getMessageIdHeader());
        assertEquals(date, message.getInternalDate());
        assertTrue(message.isSeen() && message.isAnswered() && message.isFlagged()
                && message.isDraft() && message.isDeleted());
        assertEquals(4.5, message.getSpamScore());
        assertEquals(MessageMetadata.AvStatus.INFECTED, message.getAvStatus());
    }

    @Test
    void importedMessageAccessorsAndLifecycle() {
        ExternalMailImportedMessage message = new ExternalMailImportedMessage();
        assertEquals(ExternalMailImportedMessage.Status.QUEUED, message.getStatus());
        ExternalMailAccount account = new ExternalMailAccount();
        MailFolder target = new MailFolder();
        UUID id = UUID.randomUUID();
        UUID corvexId = UUID.randomUUID();
        Instant date = Instant.parse("2025-01-01T00:00:00Z");
        message.setId(id);
        message.setAccount(account);
        message.setRemoteFolder("INBOX");
        message.setRemoteUid("22");
        message.setUIDValidity(33L);
        message.setMessageIdHeader("<id@x>");
        message.setSha256("hash");
        message.setRawRef("raw");
        message.setCorvexMessageId(corvexId);
        message.setTargetFolder(target);
        message.setStatus(ExternalMailImportedMessage.Status.FAILED);
        message.setSeen(true);
        message.setAnswered(true);
        message.setFlagged(true);
        message.setDraft(true);
        message.setDeleted(true);
        message.setPreserveTargetFolder(true);
        message.setSourceInternalDate(date);
        message.setError("failure");

        assertEquals(id, message.getId());
        assertEquals(account, message.getAccount());
        assertEquals(33L, message.getUIDValidity());
        assertEquals(corvexId, message.getCorvexMessageId());
        assertEquals(target, message.getTargetFolder());
        assertTrue(message.isSeen() && message.isAnswered() && message.isFlagged()
                && message.isDraft() && message.isDeleted() && message.isPreserveTargetFolder());
        assertEquals(date, message.getSourceInternalDate());

        message.prePersist();
        Instant importedAt = message.getImportedAt();
        assertNotNull(importedAt);
        assertNotNull(message.getUpdatedAt());
        message.prePersist();
        assertEquals(importedAt, message.getImportedAt());
        message.setUpdatedAt(date);
        message.preUpdate();
        assertTrue(message.getUpdatedAt().isAfter(date));
    }

    @Test
    void auditStateAndRunAccessorsAndLifecycle() {
        ExternalMailAccount account = new ExternalMailAccount();
        MailFolder target = new MailFolder();
        Instant at = Instant.parse("2024-01-01T00:00:00Z");

        ExternalMailAuditEvent audit = new ExternalMailAuditEvent();
        UUID auditId = UUID.randomUUID();
        audit.setId(auditId);
        audit.setAccount(account);
        audit.setActorId(UUID.randomUUID());
        audit.setOwnerId(UUID.randomUUID());
        audit.setAction("ACTION");
        audit.setDetails("details");
        audit.prePersist();
        Instant auditCreated = audit.getCreatedAt();
        audit.prePersist();
        assertEquals(auditId, audit.getId());
        assertEquals(auditCreated, audit.getCreatedAt());
        assertEquals("details", audit.getDetails());

        ExternalMailFolderState state = new ExternalMailFolderState();
        state.setId(UUID.randomUUID());
        state.setAccount(account);
        state.setRemoteFolder("INBOX");
        state.setRemoteDelimiter(".");
        state.setTargetFolder(target);
        state.setUIDValidity(9L);
        state.setLastSeenUid(99);
        state.setInitialSnapshotDone(true);
        state.setLastSyncAt(at);
        state.prePersist();
        Instant stateCreated = state.getCreatedAt();
        state.setUpdatedAt(at);
        state.preUpdate();
        assertEquals(stateCreated, state.getCreatedAt());
        assertTrue(state.getUpdatedAt().isAfter(at));
        assertEquals(99, state.getLastSeenUid());
        assertTrue(state.isInitialSnapshotDone());

        ExternalMailSyncRun run = new ExternalMailSyncRun();
        assertEquals(ExternalMailSyncRun.Trigger.SCHEDULED, run.getTriggerType());
        assertEquals(ExternalMailSyncRun.Status.RUNNING, run.getStatus());
        run.setId(UUID.randomUUID());
        run.setAccount(account);
        run.setTriggerType(ExternalMailSyncRun.Trigger.MIGRATION);
        run.setStatus(ExternalMailSyncRun.Status.PARTIAL);
        run.setFinishedAt(at);
        run.setFoldersCount(1);
        run.setFetchedCount(2);
        run.setQueuedCount(3);
        run.setSkippedCount(4);
        run.setFailedCount(5);
        run.setError("partial");
        run.prePersist();
        Instant started = run.getStartedAt();
        run.prePersist();
        assertEquals(started, run.getStartedAt());
        assertEquals(5, run.getFailedCount());
        assertEquals("partial", run.getError());
        assertNull(new ExternalMailSyncRun().getStartedAt());
    }

    @Test
    void enumContractsRemainStable() {
        assertEquals(5, ExternalMailAccount.Provider.values().length);
        assertEquals(ExternalMailAccount.AuthType.OAUTH2,
                ExternalMailAccount.AuthType.valueOf("OAUTH2"));
        assertEquals(5, ExternalMailImportedMessage.Status.values().length);
        assertEquals(4, ExternalMailSyncRun.Status.values().length);
        assertEquals(4, MessageMetadata.AvStatus.values().length);
    }
}
