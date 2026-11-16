package ru.corvex.mailstorage.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Контракты переноса почты. Оценка (estimated) и факт (migrated/skipped) всегда разделены. */
public final class MigrationDtos {

    private MigrationDtos() {
    }

    public record ScopeRequest(UUID accountId, Boolean includeAllMail, List<String> skipFolders) {
    }

    public record FolderEstimate(String remoteFolder, String specialUse,
                                 int messageCount, long estimatedBytes) {
    }

    public record PreviewView(UUID accountId,
                              boolean includeAllMail,
                              List<FolderEstimate> folders,
                              List<String> skippedFolders,
                              long totalMessages,
                              long totalBytes,
                              long quotaBytes,
                              long usedBytes,
                              boolean fits,
                              List<String> warnings) {
    }

    public record FolderView(UUID id,
                             String remoteFolder,
                             String specialUse,
                             String state,
                             int estimatedMessages,
                             long estimatedBytes,
                             int migratedMessages,
                             int skippedMessages,
                             String error) {
    }

    public record JobView(UUID id,
                          UUID accountId,
                          String accountEmail,
                          String status,
                          String mode,
                          boolean includeAllMail,
                          long estimatedMessages,
                          long estimatedBytes,
                          int migratedMessages,
                          int skippedMessages,
                          String error,
                          Instant createdAt,
                          Instant startedAt,
                          Instant finishedAt,
                          List<FolderView> folders) {
    }

    /** Отчёт: задание целиком, остаток квоты и пояснения к оценке. */
    public record ReportView(JobView job,
                             long quotaBytes,
                             long usedBytes,
                             long remainingBytes,
                             List<String> notes) {
    }
}
