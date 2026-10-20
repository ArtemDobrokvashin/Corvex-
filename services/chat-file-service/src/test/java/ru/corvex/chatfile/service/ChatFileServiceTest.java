package ru.corvex.chatfile.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chatfile.av.ClamAvClient;
import ru.corvex.chatfile.client.ChatMembershipClient;
import ru.corvex.chatfile.domain.StoredChatFile;
import ru.corvex.chatfile.security.ChatFilePrincipal;
import ru.corvex.chatfile.storage.ChatFileStorage;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatFileServiceTest {

    private static final UUID ROOM_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @TempDir
    private Path tempDir;

    @Mock
    private ChatMembershipClient memberships;

    @Mock
    private ClamAvClient clam;

    private ChatFileStorage storage;
    private ChatFileService service;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        storage = new ChatFileStorage(tempDir.toString(), mapper);
        service = new ChatFileService(memberships, storage, clam, 1024, true);
        when(memberships.requireMember(ROOM_ID, USER_ID)).thenReturn(member("MEMBER", false));
        when(clam.scan(any(InputStream.class)))
                .thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.CLEAN, null));
    }

    @Test
    void uploadCleanFilePublishesBlobAndMetadata() throws Exception {
        byte[] bytes = "hello chat".getBytes(StandardCharsets.UTF_8);

        StoredChatFile stored = service.upload(principal(), ROOM_ID, file("note.txt", "text/plain", bytes));

        assertEquals(ROOM_ID, stored.roomId());
        assertEquals(USER_ID, stored.ownerId());
        assertEquals("note.txt", stored.fileName());
        assertEquals("text/plain", stored.contentType());
        assertEquals(bytes.length, stored.sizeBytes());
        assertEquals(StoredChatFile.AvStatus.CLEAN, stored.avStatus());
        assertTrue(storage.loadMetadata(stored.id()).isPresent());
        try (InputStream input = storage.openContent(stored).orElseThrow().getInputStream()) {
            assertArrayEquals(bytes, input.readAllBytes());
        }
    }

    @Test
    void uploadDetectsPngMagicInsteadOfTrustingDeclaredType() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

        StoredChatFile stored = service.upload(principal(), ROOM_ID, file("fake.txt", "text/plain", png));

        assertEquals("image/png", stored.contentType());
    }

    @Test
    void uploadRejectsReadonlyMemberBeforeScanning() {
        when(memberships.requireMember(ROOM_ID, USER_ID)).thenReturn(member("READONLY", false));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(principal(), ROOM_ID,
                        file("note.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8))));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(clam, never()).scan(any(InputStream.class));
    }

    @Test
    void uploadRejectsInfectedFileAndDoesNotPublishMetadata() throws Exception {
        when(clam.scan(any(InputStream.class)))
                .thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.INFECTED, "Eicar-Test-Signature"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(principal(), ROOM_ID,
                        file("bad.bin", "application/octet-stream", "bad".getBytes(StandardCharsets.UTF_8))));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        try (Stream<Path> paths = Files.walk(tempDir)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().endsWith(".json")));
        }
    }

    @Test
    void uploadFailsClosedWhenAntivirusIsUnavailable() {
        when(clam.scan(any(InputStream.class)))
                .thenReturn(new ClamAvClient.ScanResult(ClamAvClient.Result.ERROR, "connection refused"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(principal(), ROOM_ID,
                        file("note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8))));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test
    void uploadEnforcesStreamingSizeLimit() {
        ChatFileService limited = new ChatFileService(memberships, storage, clam, 4, true);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> limited.upload(principal(), ROOM_ID,
                        file("large.txt", "text/plain", "12345".getBytes(StandardCharsets.UTF_8))));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatusCode());
        verify(clam, never()).scan(any(InputStream.class));
    }

    @Test
    void downloadReChecksCurrentMembership() {
        StoredChatFile stored = service.upload(principal(), ROOM_ID,
                file("note.txt", "text/plain", "shared".getBytes(StandardCharsets.UTF_8)));

        assertEquals(stored.id(), service.download(principal(), stored.fileRef()).metadata().id());

        when(memberships.requireMember(ROOM_ID, USER_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "not a member"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.download(principal(), stored.fileRef()));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void downloadWithInvalidReferenceIsNotFound() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.download(principal(), "not-a-uuid"));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private ChatFilePrincipal principal() {
        return new ChatFilePrincipal(USER_ID, Set.of(
                ChatFilePrincipal.READ,
                ChatFilePrincipal.WRITE,
                ChatFilePrincipal.SEND_FILES));
    }

    private ChatMembershipClient.Membership member(String role, boolean roomReadOnly) {
        return new ChatMembershipClient.Membership(
                ROOM_ID, USER_ID, true, role, null, roomReadOnly);
    }

    private MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }
}
