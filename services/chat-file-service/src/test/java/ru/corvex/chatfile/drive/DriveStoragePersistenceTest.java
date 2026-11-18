package ru.corvex.chatfile.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.corvex.chatfile.storage.ChatFileStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Метаданные диска пишутся и читаются одним и тем же ObjectMapper.
 *
 * <p>Тест закрепляет то, что уже ломалось: вычисляемые {@code isFolder()}/{@code isTrashed()}
 * Jackson превращал в свойства {@code folder}/{@code trashed}, записывал их в файл, а при
 * чтении падал на неизвестном поле. Файл метаданных оставался на диске, но узел молча
 * исчезал из листинга — папки выглядели пустыми.</p>
 */
class DriveStoragePersistenceTest {

    @TempDir
    Path root;

    private DriveStorage storage;
    private ObjectMapper mapper;
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Тот же голый mapper, что и в DriveServiceTest: настройки Spring здесь не помогают.
        mapper = new ObjectMapper().findAndRegisterModules();
        storage = new DriveStorage(new ChatFileStorage(root.toString(), mapper), root.toString(), mapper);
    }

    private DriveNode folder(String name) {
        Instant now = Instant.parse("2026-01-05T10:00:00Z");
        return new DriveNode(UUID.randomUUID(), owner, null, DriveNode.Kind.FOLDER, name,
                0, null, null, "CLEAN", now, now, null);
    }

    @Test
    void nodeSurvivesWriteAndRead() throws Exception {
        DriveNode node = folder("Отчёты");

        storage.saveNode(node);

        DriveNode loaded = storage.findNode(owner, node.id()).orElseThrow();
        assertEquals(node, loaded);
        assertTrue(loaded.isFolder());
        assertEquals(1, storage.listNodes(owner).size());
    }

    @Test
    void writtenJsonHasNoDerivedProperties() throws Exception {
        DriveNode node = folder("Отчёты");

        storage.saveNode(node);

        String json = Files.readString(storage.nodePath(owner, node.id()));
        assertFalse(json.contains("\"folder\""),
                "isFolder() должен быть @JsonIgnore, иначе поле попадает в файл: " + json);
        assertFalse(json.contains("\"trashed\""),
                "isTrashed() должен быть @JsonIgnore, иначе поле попадает в файл: " + json);
    }

    @Test
    void filesWrittenBeforeTheFixRemainReadable() throws Exception {
        // Именно такой JSON оставила версия без @JsonIgnore: узел уже существует у пользователя,
        // и после обновления он не должен исчезнуть из листинга.
        DriveNode node = folder("Старая папка");
        String legacy = """
                {"id":"%s","ownerId":"%s","parentId":null,"kind":"FOLDER","name":"Старая папка",
                 "sizeBytes":0,"contentType":null,"sha256":null,"avStatus":"CLEAN",
                 "createdAt":"2026-01-05T10:00:00Z","updatedAt":"2026-01-05T10:00:00Z",
                 "trashedAt":null,"folder":true,"trashed":false}
                """.formatted(node.id(), owner);
        Path path = storage.nodePath(owner, node.id());
        Files.createDirectories(path.getParent());
        Files.writeString(path, legacy);

        DriveNode loaded = storage.findNode(owner, node.id()).orElseThrow();
        assertEquals("Старая папка", loaded.name());
        assertTrue(loaded.isFolder());
        assertEquals(1, storage.listNodes(owner).size());
    }

    @Test
    void shareSurvivesWriteAndRead() throws Exception {
        Instant now = Instant.parse("2026-01-05T10:00:00Z");
        DriveShare share = new DriveShare(UUID.randomUUID(), UUID.randomUUID(), owner,
                "hash", now, null, null);

        storage.saveShare(share);

        DriveShare loaded = storage.findShare("hash").orElseThrow();
        assertEquals(share, loaded);
        assertTrue(loaded.isActive(now));
        assertFalse(loaded.isRevoked());
    }
}
