package ru.corvex.mailstorage.api.dto;

import ru.corvex.mailstorage.migration.MigrationBulkCsv;

import java.util.List;
import java.util.UUID;

/** Контракты админского экрана «Перенос»: сводка по сотрудникам, CSV и квота. */
public final class AdminMigrationDtos {

    private AdminMigrationDtos() {
    }

    public record Row(UUID ownerId,
                      String email,
                      String displayName,
                      String invitationStatus,
                      String migrationState,
                      String jobStatus,
                      UUID jobId,
                      int migratedMessages,
                      long quotaBytes,
                      long usedBytes,
                      String sourceHost,
                      String sourceLogin) {
    }

    public record Totals(int employees,
                         int invited,
                         int notStarted,
                         int running,
                         int done,
                         int failed,
                         long migratedMessages) {
    }

    public record Summary(List<Row> rows, Totals totals, List<String> notes) {
    }

    public record BulkResult(int rows,
                             int invited,
                             int skipped,
                             boolean truncated,
                             List<MigrationBulkCsv.Issue> issues) {
    }

    /** Либо новое значение квоты, либо прибавка к текущей — второе удобнее в отчёте. */
    public record QuotaBumpRequest(Long quotaBytes, Long addBytes) {
    }

    public record QuotaBumpResult(UUID ownerId, long quotaBytes, long usedBytes, boolean fits,
                                  long remainingBytes) {
    }
}
