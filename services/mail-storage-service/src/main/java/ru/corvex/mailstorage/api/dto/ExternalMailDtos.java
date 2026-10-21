package ru.corvex.mailstorage.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.corvex.mailstorage.domain.ExternalMailAccount;
import ru.corvex.mailstorage.domain.ExternalMailSyncRun;

import java.time.Instant;
import java.util.UUID;

/** API-контракты External Mail Connector. Secret только write-only в запросе; в ответах его нет. */
public final class ExternalMailDtos {

    private ExternalMailDtos() {
    }

    public record PresetView(
            ExternalMailAccount.Provider provider,
            String imapHost,
            int imapPort,
            String pop3Host,
            int pop3Port,
            String smtpHost,
            int smtpPort,
            ExternalMailAccount.Security incomingSecurity,
            ExternalMailAccount.Security smtpSecurity,
            String hint) {
    }

    /** Полная форма аккаунта для create/update/test. При update пустой secret = оставить сохранённый. */
    public record AccountRequest(
            UUID existingAccountId,
            UUID targetUserId,
            ExternalMailAccount.OwnerKind ownerKind,
            String label,
            String emailAddress,
            String displayName,
            ExternalMailAccount.Provider provider,
            ExternalMailAccount.AuthType authType,
            ExternalMailAccount.Protocol incomingProtocol,
            String incomingHost,
            Integer incomingPort,
            ExternalMailAccount.Security incomingSecurity,
            String incomingUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String secret,
            Boolean smtpEnabled,
            String smtpHost,
            Integer smtpPort,
            ExternalMailAccount.Security smtpSecurity,
            String smtpUsername,
            UUID targetFolderId,
            Boolean enabled,
            Boolean syncEnabled,
            Boolean sendEnabled,
            ExternalMailAccount.InitialSyncMode initialSyncMode,
            ExternalMailAccount.SyncScope syncScope,
            Integer pollIntervalSeconds,
            Boolean leaveOnServer,
            Boolean markSeenOnServer,
            Boolean deleteAfterFetch,
            Boolean saveSentOnServer,
            String sentFolderName,
            Boolean testBeforeSave) {
    }

    public record AccountView(
            UUID id,
            UUID ownerId,
            UUID createdBy,
            ExternalMailAccount.OwnerKind ownerKind,
            String label,
            String emailAddress,
            String displayName,
            ExternalMailAccount.Provider provider,
            ExternalMailAccount.AuthType authType,
            ExternalMailAccount.Protocol incomingProtocol,
            String incomingHost,
            int incomingPort,
            ExternalMailAccount.Security incomingSecurity,
            String incomingUsername,
            boolean secretConfigured,
            boolean smtpEnabled,
            String smtpHost,
            Integer smtpPort,
            ExternalMailAccount.Security smtpSecurity,
            String smtpUsername,
            UUID targetFolderId,
            boolean enabled,
            boolean syncEnabled,
            boolean sendEnabled,
            ExternalMailAccount.InitialSyncMode initialSyncMode,
            ExternalMailAccount.SyncScope syncScope,
            int pollIntervalSeconds,
            boolean leaveOnServer,
            boolean markSeenOnServer,
            boolean deleteAfterFetch,
            boolean saveSentOnServer,
            String sentFolderName,
            Instant lastSyncAt,
            Instant lastSuccessAt,
            Instant nextSyncAt,
            String lastError,
            int consecutiveFailures,
            boolean syncRunning,
            long queuedCount,
            long deliveredCount,
            long failedCount,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record EnabledRequest(Boolean enabled, Boolean syncEnabled, Boolean sendEnabled) {
    }

    public record TestConnectionResponse(
            boolean incomingOk,
            String incomingMessage,
            int folderCount,
            int inboxMessageCount,
            boolean smtpTested,
            boolean smtpOk,
            String smtpMessage) {
    }

    public record SyncRunView(
            UUID id,
            UUID accountId,
            ExternalMailSyncRun.Trigger trigger,
            ExternalMailSyncRun.Status status,
            Instant startedAt,
            Instant finishedAt,
            int foldersCount,
            int fetchedCount,
            int queuedCount,
            int skippedCount,
            int failedCount,
            String error) {
    }

    public record AuditView(UUID id, UUID accountId, UUID actorId, UUID ownerId,
                            String action, String details, Instant createdAt) {
    }

    /** Только internal API для mail-smtp-service: секрет выдаётся just-in-time по internal token. */
    public record TransportConfigView(
            UUID accountId,
            UUID ownerId,
            String emailAddress,
            String displayName,
            ExternalMailAccount.Provider provider,
            ExternalMailAccount.Protocol incomingProtocol,
            String incomingHost,
            int incomingPort,
            ExternalMailAccount.Security incomingSecurity,
            String incomingUsername,
            String secret,
            String smtpHost,
            Integer smtpPort,
            ExternalMailAccount.Security smtpSecurity,
            String smtpUsername,
            boolean saveSentOnServer,
            String sentFolderName) {
    }
}
