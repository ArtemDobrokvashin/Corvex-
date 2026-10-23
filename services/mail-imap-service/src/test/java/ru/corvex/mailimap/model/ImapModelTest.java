package ru.corvex.mailimap.model;

import org.junit.jupiter.api.Test;
import ru.corvex.mailimap.client.ImapStorageClient;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void segmentsAndInboxDetectionTolerateBlankAndRepeatedDelimiters() throws Exception {
        assertEquals(List.of(), ImapFolderPaths.segments(null));
        assertEquals(List.of(), ImapFolderPaths.segments("  "));
        assertEquals(List.of("Проекты", "2026"), ImapFolderPaths.segments(" / Проекты // 2026 / "));
        assertEquals('/', ImapFolderPaths.DELIMITER);
        assertFalse(ImapFolderPaths.isInboxPath("INBOX/Archive"));
        assertFalse(ImapFolderPaths.isInboxPath(null));

        var pathsConstructor = ImapFolderPaths.class.getDeclaredConstructor();
        pathsConstructor.setAccessible(true);
        assertNotNull(pathsConstructor.newInstance());
        var flagsConstructor = ImapFlags.class.getDeclaredConstructor();
        flagsConstructor.setAccessible(true);
        assertNotNull(flagsConstructor.newInstance());
    }

    @Test
    void resolvesInboxBySpecialUseAndItsChildren() {
        UUID localizedInboxId = UUID.randomUUID();
        UUID archiveId = UUID.randomUUID();
        var folders = List.of(
                new ImapStorageClient.FolderInfo(localizedInboxId, null, "Входящие", "INBOX", 2, 1, 1, 1, 1),
                new ImapStorageClient.FolderInfo(archiveId, localizedInboxId, "Archive", null, 1, 2, 0, 0, 0));

        assertEquals(localizedInboxId, ImapFolderPaths.resolve("inbox", folders).orElseThrow().id());
        assertEquals(archiveId, ImapFolderPaths.resolve("INBOX/archive", folders).orElseThrow().id());
        assertEquals("INBOX", ImapFolderPaths.toImapPath(folders.get(0), folders));
        assertEquals("INBOX/Archive", ImapFolderPaths.toImapPath(folders.get(1), folders));
        assertTrue(ImapFolderPaths.resolve("INBOX/Missing", folders).isEmpty());
        assertTrue(ImapFolderPaths.resolve("Other/Archive", folders).isEmpty());
    }

    @Test
    void toImapPathStopsAtMissingParentKeepsNestedInboxNameAndBreaksCycles() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        var parent = new ImapStorageClient.FolderInfo(parentId, UUID.randomUUID(), "Parent", null, 1, 1, 0, 0, 0);
        var nestedInbox = new ImapStorageClient.FolderInfo(childId, parentId, "inbox", null, 1, 2, 0, 0, 0);
        assertEquals("Parent/inbox", ImapFolderPaths.toImapPath(nestedInbox, List.of(parent, nestedInbox)));
        assertEquals("Parent", ImapFolderPaths.toImapPath(parent, List.of(parent, nestedInbox)),
                "отсутствующий родитель прекращает подъём, а не роняет LIST");

        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var first = new ImapStorageClient.FolderInfo(firstId, secondId, "First", null, 1, 1, 0, 0, 0);
        var second = new ImapStorageClient.FolderInfo(secondId, firstId, "Second", null, 1, 1, 0, 0, 0);
        String cyclic = ImapFolderPaths.toImapPath(first, List.of(first, second));
        assertEquals(64, cyclic.split("/").length, "guard защищает от повреждённого циклического дерева");
    }

    @Test
    void flagsCoverEveryPersistentColumnAndNormalizationEdgeCases() {
        var everyFlag = new ImapStorageClient.MessageMeta(UUID.randomUUID(), 1, 1, "<a@b>",
                "ivanov@corp.local", "Тема", Instant.now(), 42, true, true, true, true, true);
        assertEquals(ImapFlags.SUPPORTED, ImapFlags.fromMeta(everyFlag));
        assertTrue(ImapFlags.normalize(null).isEmpty());

        Set<String> withNullAndNoise = new java.util.LinkedHashSet<>(
                java.util.Arrays.asList(" \\seen ", null, "\\ANSWERED", "$ProjectX", "\\Recent", "\\Deleted"));
        assertEquals(Set.of("\\Seen", "\\Answered", "\\Deleted"), ImapFlags.normalize(withNullAndNoise));

        var addNothing = ImapFlags.add(null);
        assertNull(addNothing.seen());
        assertNull(addNothing.answered());
        assertNull(addNothing.flagged());
        assertNull(addNothing.draft());
        assertNull(addNothing.deleted());

        var removeAll = ImapFlags.remove(ImapFlags.SUPPORTED);
        assertEquals(Boolean.FALSE, removeAll.seen());
        assertEquals(Boolean.FALSE, removeAll.answered());
        assertEquals(Boolean.FALSE, removeAll.flagged());
        assertEquals(Boolean.FALSE, removeAll.draft());
        assertEquals(Boolean.FALSE, removeAll.deleted());

        var replaceAll = ImapFlags.replaceWith(ImapFlags.SUPPORTED);
        assertEquals(Boolean.TRUE, replaceAll.seen());
        assertEquals(Boolean.TRUE, replaceAll.answered());
        assertEquals(Boolean.TRUE, replaceAll.flagged());
        assertEquals(Boolean.TRUE, replaceAll.draft());
        assertEquals(Boolean.TRUE, replaceAll.deleted());
    }
}
