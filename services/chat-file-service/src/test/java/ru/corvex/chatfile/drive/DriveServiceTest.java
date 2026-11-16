package ru.corvex.chatfile.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chatfile.av.ClamAvClient;
import ru.corvex.chatfile.storage.ChatFileStorage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Поведение диска без Spring-контекста: реальное файловое хранилище во временном каталоге,
 * ClamAV подменён, поэтому проверяется наша логика, а не сеть.
 */
class DriveServiceTest {

    @TempDir
    Path root;

    private DriveStorage storage;
    private ClamAvClient clam;
    private DriveService service;
    private final UUID user = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ChatFileStorage blobs = new ChatFileStorage(root.toString(), mapper);
        storage = new DriveStorage(blobs, root.toString(), mapper);
        clam = mock(ClamAvClient.class);
        when(clam.scan(any())).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.CLEAN, null));
        service = new DriveService(storage, clam, 1024 * 1024, 1024 * 1024, true);
    }

    @Test
    void uploadsListsAndDownloadsFile() {
        DriveNode node = service.upload(user, null, file("report.txt", "hello"));

        assertEquals("report.txt", node.name());
        assertEquals("text/plain", node.contentType());
        assertEquals("CLEAN", node.avStatus());
        assertEquals(1, service.list(user, null, DriveService.View.FILES, null).size());

        DriveService.Download download = service.download(user, node.id());
        assertEquals(node.id(), download.node().id());
        assertTrue(download.resource().exists());
    }

    @Test
    void anotherUserCannotSeeTheFile() {
        DriveNode node = service.upload(user, null, file("private.txt", "secret"));

        assertEquals(0, service.list(other, null, DriveService.View.FILES, null).size());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.download(other, node.id()));
    }

    @Test
    void duplicateNameInSameFolderIsRejected() {
        service.upload(user, null, file("dup.txt", "one"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(user, null, file("DUP.txt", "two")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void infectedFileIsNotPublished() {
        when(clam.scan(any())).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.INFECTED, "Eicar"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(user, null, file("eicar.txt", "X5O!P%@AP")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        assertTrue(service.list(user, null, DriveService.View.FILES, null).isEmpty());
    }

    @Test
    void unavailableAntivirusBlocksUploadWhenFailClosed() {
        when(clam.scan(any())).thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.ERROR, "timeout"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(user, null, file("clean.txt", "data")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void quotaIsCheckedBeforePublishing() {
        DriveService small = new DriveService(storage, clam, 8, 1024 * 1024, true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> small.upload(user, null, file("big.txt", "more than eight bytes")));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
        assertTrue(small.list(user, null, DriveService.View.FILES, null).isEmpty());
    }

    @Test
    void trashHidesSubtreeAndRestoreBringsItBack() {
        DriveNode folder = service.createFolder(user, null, "Отчёты");
        DriveNode inner = service.createFolder(user, folder.id(), "2026");
        DriveNode file = service.upload(user, inner.id(), file("q1.txt", "data"));

        service.trash(user, folder.id());

        assertTrue(service.list(user, null, DriveService.View.FILES, null).isEmpty());
        List<DriveNode> trash = service.list(user, null, DriveService.View.TRASH, null);
        assertEquals(1, trash.size(), "в корзине виден только верхний уровень");
        assertEquals(folder.id(), trash.get(0).id());
        assertStatus(HttpStatus.CONFLICT, () -> service.download(user, file.id()));

        service.trash(user, folder.id());
        service.restore(user, folder.id());

        assertEquals(1, service.list(user, null, DriveService.View.FILES, null).size());
        assertEquals(1, service.list(user, folder.id(), DriveService.View.FILES, null).size());
        assertEquals(1, service.list(user, inner.id(), DriveService.View.FILES, null).size());
    }

    @Test
    void restoreFailsWhileParentIsStillInTrash() {
        DriveNode folder = service.createFolder(user, null, "Проект");
        DriveNode inner = service.createFolder(user, folder.id(), "Вложенная");

        service.trash(user, folder.id());
        assertStatus(HttpStatus.CONFLICT, () -> service.restore(user, inner.id()));
    }

    @Test
    void moveRejectsOwnDescendant() {
        DriveNode parent = service.createFolder(user, null, "A");
        DriveNode child = service.createFolder(user, parent.id(), "B");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.move(user, parent.id(), child.id()));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void purgeRequiresTrashAndRemovesBlob() {
        DriveNode node = service.upload(user, null, file("old.txt", "data"));
        assertStatus(HttpStatus.CONFLICT, () -> service.purge(user, node.id()));

        service.trash(user, node.id());
        service.purge(user, node.id());

        assertTrue(storage.listNodes(user).isEmpty());
        assertFalse(storage.blobs().blobExists(node.sha256()));
    }

    @Test
    void shareIsPrivateUntilExplicitAndRevocable() {
        DriveNode node = service.upload(user, null, file("shared.txt", "payload"));

        assertEquals(0, service.shares(user, node.id()).size());
        DriveService.ShareCreated created = service.share(user, node.id(), null);
        assertNotNull(created.token());

        assertEquals("payload", read(service.downloadByToken(created.token())));
        assertStatus(HttpStatus.NOT_FOUND, () -> service.downloadByToken("not-a-token"));

        service.revokeShare(user, created.share().id());
        assertStatus(HttpStatus.NOT_FOUND, () -> service.downloadByToken(created.token()));
    }

    @Test
    void expiredShareStopsWorking() throws Exception {
        DriveNode node = service.upload(user, null, file("expiring.txt", "payload"));
        DriveService.ShareCreated created = service.share(user, node.id(), 1);

        // Сдвигаем срок в прошлое, не дожидаясь суток: проверяется именно логика отзыва по времени.
        DriveShare expired = new DriveShare(created.share().id(), created.share().nodeId(),
                created.share().ownerId(), created.share().tokenHash(),
                created.share().createdAt(), Instant.now().minus(Duration.ofMinutes(1)), null);
        storage.saveShare(expired);

        assertStatus(HttpStatus.NOT_FOUND, () -> service.downloadByToken(created.token()));
    }

    @Test
    void folderShareIsRejected() {
        DriveNode folder = service.createFolder(user, null, "Docs");
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.share(user, folder.id(), null));
    }

    @Test
    void namesCannotEscapeTheFolder() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createFolder(user, null, "../etc"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createFolder(user, null, "CON"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> service.createFolder(user, null, "a<b"));
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(DriveService.Download download) {
        try (var input = download.resource().getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertStatus(HttpStatus expected, Runnable action) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(expected, error.getStatusCode());
    }
}
