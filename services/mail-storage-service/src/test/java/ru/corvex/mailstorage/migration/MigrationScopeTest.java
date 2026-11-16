package ru.corvex.mailstorage.migration;

import org.junit.jupiter.api.Test;
import ru.corvex.mailstorage.domain.ExternalMailAccount;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationScopeTest {

    @Test
    void gmailDuplicatesAreSkippedByNameBecauseSpecialUseIsMissing() {
        MigrationScope scope = new MigrationScope(ExternalMailAccount.Provider.GMAIL, false, List.of());

        assertTrue(scope.skip("[Gmail]/All Mail", "ARCHIVE"));
        assertTrue(scope.skip("[Gmail]/Important", null));
        assertTrue(scope.skip("[Gmail]/Starred", null));
        assertFalse(scope.skip("[Gmail]/Sent Mail", "SENT"));
        assertFalse(scope.skip("Проект", null));
    }

    @Test
    void allMailKeptWhenUserExplicitlyAsksForIt() {
        MigrationScope scope = new MigrationScope(ExternalMailAccount.Provider.GMAIL, true, List.of());

        assertFalse(scope.skip("[Gmail]/All Mail", "ARCHIVE"));
        assertFalse(scope.skip("[Gmail]/Starred", null));
    }

    @Test
    void archiveFolderOfOtherProvidersIsNotGmailDuplicate() {
        MigrationScope scope = new MigrationScope(ExternalMailAccount.Provider.MAIL_RU, false, List.of());

        assertFalse(scope.skip("Archive", "ARCHIVE"));
        assertFalse(scope.skip("Вся почта", "ARCHIVE"));
    }

    @Test
    void mySkipFoldersAreCaseInsensitiveAndWinOverIncludeAllMail() {
        MigrationScope scope = new MigrationScope(ExternalMailAccount.Provider.GMAIL, true,
                List.of(" Архив ", "[gmail]/all mail"));

        assertTrue(scope.skip("АРХИВ", null));
        assertTrue(scope.skip("[Gmail]/All Mail", "ARCHIVE"));
    }

    @Test
    void estimateIsExactWhenSampleCoversFolder() {
        assertEquals(3000, MigrationScope.estimateBytes(3, List.of(1000L, 1000L, 1000L)));
    }

    @Test
    void estimateUsesAverageForLargeFolder() {
        // Два письма по 1000 и 3000 байт, всего 100 писем: средняя 2000 × 100.
        assertEquals(200_000, MigrationScope.estimateBytes(100, List.of(1000L, 3000L)));
    }

    @Test
    void estimateIgnoresEmptyAndZeroSizes() {
        assertEquals(0, MigrationScope.estimateBytes(10, List.of(0L, 0L)));
        assertEquals(0, MigrationScope.estimateBytes(0, List.of(1000L)));
        assertEquals(2000, MigrationScope.estimateBytes(2, List.of(0L, 1000L)));
    }

    @Test
    void sampleSizeNeverExceedsFolderOrLimit() {
        assertEquals(0, MigrationScope.sampleSize(0, 50));
        assertEquals(10, MigrationScope.sampleSize(10, 50));
        assertEquals(50, MigrationScope.sampleSize(5000, 50));
    }

    @Test
    void skipFolderListRoundTripsThroughDatabaseColumn() {
        String stored = MigrationScope.joinCsv(List.of(" Архив ", "", "[Gmail]/All Mail"));

        assertEquals(List.of("[gmail]/all mail", "архив"), MigrationScope.splitCsv(stored));
        assertFalse(MigrationScope.splitCsv(null).iterator().hasNext());
    }
}
