package ru.corvex.mailimap.model;

import org.junit.jupiter.api.Test;
import ru.corvex.mailimap.client.ImapStorageClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Модельный слой IMAP: пути папок (иерархия '/'), INBOX без регистра, семантика STORE +/-FLAGS. */
class ImapModelTest {

    private final UUID inboxId = UUID.randomUUID();
    private final UUID projectsId = UUID.randomUUID();
    private final UUID y2026Id = UUID.randomUUID();

    private List<ImapStorageClient.FolderInfo> tree() {
        return List.of(
                new ImapStorageClient.FolderInfo(inboxId, null, "INBOX", "INBOX", 10, 1, 9, 3, 1),
                new ImapStorageClient.FolderInfo(projectsId, null, "Проекты", null, 5, 2, 4, 1, 0),
                new ImapStorageClient.FolderInfo(y2026Id, projectsId, "2026", null, 2, 3, 1, 0, 0),
                new ImapStorageClient.FolderInfo(UUID.randomUUID(), null, "Sent", "SENT", 7, 4, 6, 2, 0));
    }

    @Test
    void resolvesInboxCaseInsensitively() {
        assertTrue(ImapFolderPaths.isInboxPath("inbox"));
        assertEquals(inboxId, ImapFolderPaths.resolve("InBox", tree()).orElseThrow().id());
    }

    @Test
    void resolvesNestedUnicodePath() {
        assertEquals(y2026Id, ImapFolderPaths.resolve("Проекты/2026", tree()).orElseThrow().id());
        assertEquals(y2026Id, ImapFolderPaths.resolve("проекты/2026/", tree()).orElseThrow().id(),
                "сравнение без учёта регистра и терпимость к хвостовому разделителю");
    }

    @Test
    void unknownPathResolvesEmpty() {
        assertTrue(ImapFolderPaths.resolve("Проекты/2077", tree()).isEmpty());
        assertTrue(ImapFolderPaths.resolve("", tree()).isEmpty());
    }

    @Test
    void buildsImapPathBackFromTree() {
        var y2026 = tree().stream().filter(f -> f.id().equals(y2026Id)).findFirst().orElseThrow();
        assertEquals("Проекты/2026", ImapFolderPaths.toImapPath(y2026, tree()));
        var inbox = tree().stream().filter(f -> f.id().equals(inboxId)).findFirst().orElseThrow();
        assertEquals("INBOX", ImapFolderPaths.toImapPath(inbox, tree()));
    }

    private ImapStorageClient.MessageMeta meta(boolean seen, boolean flagged, boolean deleted) {
        return new ImapStorageClient.MessageMeta(UUID.randomUUID(), 1, 1, "<a@b>", "ivanov@corp.local",
                "Тема", Instant.now(), 42, seen, false, flagged, false, deleted);
    }

    @Test
    void flagsFromMetaUseRfcNames() {
        assertEquals(Set.of("\\Seen", "\\Flagged"), ImapFlags.fromMeta(meta(true, true, false)));
    }

    @Test
    void storePlusFlagsSetsOnlyListed() {
        var update = ImapFlags.add(Set.of("\\seen"));
        assertEquals(Boolean.TRUE, update.seen());
        assertNull(update.flagged(), "не перечисленные флаги STORE +FLAGS не трогает");
        assertNull(update.deleted());
    }

    @Test
    void storeMinusFlagsClearsOnlyListed() {
        var update = ImapFlags.remove(Set.of("\\Deleted"));
        assertEquals(Boolean.FALSE, update.deleted());
        assertNull(update.seen());
    }

    @Test
    void storeReplaceIsFullSnapshotAndIgnoresKeywords() {
        var update = ImapFlags.replaceWith(Set.of("\\Flagged", "$Forwarded", "\\Recent"));
        assertEquals(Boolean.TRUE, update.flagged());
        assertEquals(Boolean.FALSE, update.seen(), "полная замена: отсутствующий флаг снимается");
        assertFalse(ImapFlags.SUPPORTED.contains("$Forwarded"));
    }
}
