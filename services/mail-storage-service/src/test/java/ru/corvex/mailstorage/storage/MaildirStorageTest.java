package ru.corvex.mailstorage.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaildirStorageTest {

    @TempDir
    Path tmp;

    private Path mailRoot;
    private Path attachmentRoot;
    private MaildirStorage storage;

    @BeforeEach
    void setUp() {
        mailRoot = tmp.resolve("mail");
        attachmentRoot = tmp.resolve("attachments");
        storage = new MaildirStorage(mailRoot, attachmentRoot);
    }

    @Test
    void storesUnseenAndSeenMessagesInDifferentMaildirBuckets() throws Exception {
        Path unseen = storage.storeMessage("Иван Иванов@company.local", "INBOX",
                bytes("first"));
        Path seen = storage.storeMessage("Иван Иванов@company.local", "INBOX",
                bytes("second"), true);

        assertEquals("first", Files.readString(mailRoot.resolve(unseen), StandardCharsets.UTF_8));
        assertEquals("second", Files.readString(mailRoot.resolve(seen), StandardCharsets.UTF_8));
        assertTrue(unseen.toString().contains("new"));
        assertTrue(seen.toString().contains("cur"));
        assertFalse(unseen.toString().contains(" "), "mailbox и folder должны санитизироваться для ФС");
        assertTrue(Files.isDirectory(mailRoot.resolve(unseen).getParent().getParent().resolve("tmp")));
    }

    @Test
    void removesTemporaryMessageWhenInputFails() throws Exception {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk went away");
            }
        };

        assertThrows(IOException.class,
                () -> storage.storeMessage("user@company.local", "INBOX", broken));

        Path tmpDir = mailRoot.resolve("user@company.local").resolve("INBOX").resolve("tmp");
        try (Stream<Path> files = Files.list(tmpDir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void attachmentStorageDeduplicatesBySha256() throws Exception {
        MaildirStorage.StoredAttachment first = storage.storeAttachment(bytes("same-bytes"));
        MaildirStorage.StoredAttachment second = storage.storeAttachment(bytes("same-bytes"));

        assertEquals(first, second);
        assertEquals(10, first.sizeBytes());
        Path stored = attachmentRoot
                .resolve(first.sha256().substring(0, 2))
                .resolve(first.sha256().substring(2, 4))
                .resolve(first.sha256());
        assertEquals("same-bytes", Files.readString(stored, StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.list(attachmentRoot)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")),
                    "временный upload не должен оставаться после дедупликации");
        }
    }

    @Test
    void attachmentStorageCleansTemporaryFileOnReadFailure() throws Exception {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken attachment");
            }
        };

        assertThrows(IOException.class, () -> storage.storeAttachment(broken));

        try (Stream<Path> files = Files.list(attachmentRoot)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void opensOnlyPathsInsideMailRoot() throws Exception {
        Path relative = storage.storeMessage("user@company.local", "INBOX", bytes("hello"));

        try (InputStream in = storage.openMessage(relative.toString())) {
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertThrows(IllegalArgumentException.class, () -> storage.openMessage("../outside.eml"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.resolveMessagePath(Path.of("..", "outside.eml").toString()));
    }

    @Test
    void movingMessageChangesFolderAndSeenBucket() throws Exception {
        Path source = storage.storeMessage("user@company.local", "INBOX", bytes("move me"));

        Path moved = storage.moveMessageToFolder(source.toString(), "Archive", true);

        assertFalse(Files.exists(mailRoot.resolve(source)));
        assertEquals("move me", Files.readString(mailRoot.resolve(moved), StandardCharsets.UTF_8));
        assertTrue(moved.toString().contains("Archive"));
        assertTrue(moved.toString().contains("cur"));
    }

    @Test
    void movingMessageWithinSameBucketIsStableAndCollisionGetsNewName() throws Exception {
        Path source = storage.storeMessage("user@company.local", "INBOX", bytes("same"));
        Path unchanged = storage.moveMessageToFolder(source.toString(), "INBOX", false);
        assertEquals(source, unchanged);
        assertTrue(Files.exists(mailRoot.resolve(source)));

        String fileName = source.getFileName().toString();
        Path collision = mailRoot.resolve("user@company.local").resolve("Archive").resolve("new").resolve(fileName);
        Files.createDirectories(collision.getParent());
        Files.writeString(collision, "already there", StandardCharsets.UTF_8);

        Path moved = storage.moveMessageToFolder(source.toString(), "Archive", false);

        assertFalse(moved.getFileName().toString().equals(fileName));
        assertEquals("already there", Files.readString(collision, StandardCharsets.UTF_8));
        assertEquals("same", Files.readString(mailRoot.resolve(moved), StandardCharsets.UTF_8));
    }

    @Test
    void copyingMessageLeavesSourceAndCreatesIndependentFile() throws Exception {
        Path source = storage.storeMessage("user@company.local", "INBOX", bytes("copy me"), true);

        Path copy = storage.copyMessageToFolder(source.toString(), "Archive", false);

        assertTrue(Files.exists(mailRoot.resolve(source)));
        assertEquals("copy me", Files.readString(mailRoot.resolve(copy), StandardCharsets.UTF_8));
        assertTrue(copy.toString().contains("new"));
        assertFalse(copy.getFileName().equals(source.getFileName()));
    }

    @Test
    void deletesMessageAndIgnoresMissingFile() throws Exception {
        Path relative = storage.storeMessage("user@company.local", "Trash", bytes("gone"));

        storage.deleteMessage(relative.toString());
        storage.deleteMessage(relative.toString());

        assertFalse(Files.exists(mailRoot.resolve(relative)));
    }

    @Test
    void renamesFolderDirectoryAndRejectsExistingDestination() throws Exception {
        Path stored = storage.storeMessage("user@company.local", "Projects", bytes("mail"));
        Path oldDir = mailRoot.resolve("user@company.local").resolve("Projects");

        storage.renameFolderDir("user@company.local", "Projects", "Projects");
        assertTrue(Files.exists(mailRoot.resolve(stored)));

        storage.renameFolderDir("user@company.local", "Projects", "Renamed");
        assertFalse(Files.exists(oldDir));
        assertTrue(Files.isDirectory(mailRoot.resolve("user@company.local").resolve("Renamed")));

        storage.storeMessage("user@company.local", "Second", bytes("other"));
        assertThrows(FileAlreadyExistsException.class,
                () -> storage.renameFolderDir("user@company.local", "Second", "Renamed"));
        storage.renameFolderDir("user@company.local", "Missing", "Anything");
    }

    @Test
    void deletesFolderDirectoryRecursively() throws Exception {
        storage.storeMessage("user@company.local", "Disposable", bytes("one"));
        storage.storeMessage("user@company.local", "Disposable", bytes("two"), true);
        Path dir = mailRoot.resolve("user@company.local").resolve("Disposable");

        storage.deleteFolderDir("user@company.local", "Disposable");
        storage.deleteFolderDir("user@company.local", "Disposable");

        assertFalse(Files.exists(dir));
    }

    @Test
    void rejectsUnsafePathSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> storage.storeMessage(null, "INBOX", bytes("x")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.storeMessage("user@company.local", "", bytes("x")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.storeMessage("user@company.local", ".", bytes("x")));
        assertThrows(IllegalArgumentException.class,
                () -> storage.storeMessage("user@company.local", "../escape", bytes("x")));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
