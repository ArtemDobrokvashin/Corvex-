package ru.corvex.chatfile.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.corvex.chatfile.domain.StoredChatFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileStorageTest {

    private static final String SHA_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    private Path tempDir;

    private ChatFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new ChatFileStorage(
                tempDir.toString(),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void stageComputesShaSizeAndPrefix() throws Exception {
        ChatFileStorage.StagedUpload staged = storage.stage(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), 1024);

        assertEquals(3, staged.sizeBytes());
        assertEquals(SHA_ABC, staged.sha256());
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), staged.prefix());
        assertTrue(Files.isRegularFile(staged.path()));
        storage.deleteStaged(staged);
    }

    @Test
    void stageRejectsOversizeAndDeletesPartialFile() throws Exception {
        assertThrows(ChatFileStorage.FileTooLargeException.class,
                () -> storage.stage(new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)), 5));

        try (Stream<Path> paths = Files.walk(tempDir)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void publishDeduplicatesIdenticalContent() throws Exception {
        ChatFileStorage.StagedUpload first = storage.stage(
                new ByteArrayInputStream("same".getBytes(StandardCharsets.UTF_8)), 1024);
        ChatFileStorage.StagedUpload second = storage.stage(
                new ByteArrayInputStream("same".getBytes(StandardCharsets.UTF_8)), 1024);

        Path firstPath = storage.publishBlob(first);
        Path secondPath = storage.publishBlob(second);

        assertEquals(firstPath, secondPath);
        try (InputStream input = Files.newInputStream(firstPath)) {
            assertArrayEquals("same".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
        }
    }

    @Test
    void metadataRoundTripsThroughJson() throws Exception {
        StoredChatFile metadata = new StoredChatFile(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Отчёт.pdf",
                "application/pdf",
                3,
                SHA_ABC,
                StoredChatFile.AvStatus.CLEAN,
                null,
                Instant.parse("2026-10-20T09:00:00Z"));

        storage.writeMetadata(metadata);

        assertEquals(metadata, storage.loadMetadata(metadata.id()).orElseThrow());
    }

    @Test
    void blobPathRejectsInvalidHash() {
        assertThrows(IllegalArgumentException.class, () -> storage.blobPath("../secret"));
    }
}
