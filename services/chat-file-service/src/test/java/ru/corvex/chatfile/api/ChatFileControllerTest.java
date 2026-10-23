package ru.corvex.chatfile.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.corvex.chatfile.domain.StoredChatFile;
import ru.corvex.chatfile.security.ChatFilePrincipal;
import ru.corvex.chatfile.service.ChatFileService;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatFileControllerTest {

    private static final UUID FILE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ROOM_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-10-23T07:30:00Z");

    private ChatFileService files;
    private ChatFileController controller;

    @BeforeEach
    void setUp() {
        files = mock(ChatFileService.class);
        controller = new ChatFileController(files);
    }

    @Test
    void uploadDelegatesJwtPrincipalRoomAndFileToService() {
        StoredChatFile stored = stored("application/pdf");
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "%PDF".getBytes(StandardCharsets.UTF_8));
        when(files.upload(org.mockito.ArgumentMatchers.any(ChatFilePrincipal.class), eq(ROOM_ID), eq(file)))
                .thenReturn(stored);

        ChatFileDtos.ChatFileUploadView result = controller.upload(jwt(), ROOM_ID, file);

        assertEquals(FILE_ID.toString(), result.fileRef());
        assertEquals(ROOM_ID, result.roomId());
        assertEquals(USER_ID, result.ownerId());
        assertEquals("report.pdf", result.fileName());
        assertEquals("application/pdf", result.contentType());
        assertEquals(123, result.sizeBytes());
        assertEquals("a".repeat(64), result.sha256());
        assertEquals(CREATED_AT, result.createdAt());

        ArgumentCaptor<ChatFilePrincipal> principal = ArgumentCaptor.forClass(ChatFilePrincipal.class);
        verify(files).upload(principal.capture(), eq(ROOM_ID), eq(file));
        assertEquals(USER_ID, principal.getValue().userId());
        assertTrue(principal.getValue().has(ChatFilePrincipal.SEND_FILES));
    }

    @Test
    void downloadReturnsResourceWithPrivateNoStoreHeaders() {
        StoredChatFile metadata = stored("application/pdf");
        Resource resource = new ByteArrayResource("file-bytes".getBytes(StandardCharsets.UTF_8));
        when(files.download(org.mockito.ArgumentMatchers.any(ChatFilePrincipal.class), eq(FILE_ID.toString())))
                .thenReturn(new ChatFileService.Download(metadata, resource));

        ResponseEntity<Resource> response = controller.download(jwt(), FILE_ID.toString());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(resource, response.getBody());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals(123, response.getHeaders().getContentLength());
        assertEquals("\"" + "a".repeat(64) + "\"", response.getHeaders().getETag());
        assertEquals("no-store, private", response.getHeaders().getCacheControl());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"));
        assertTrue(disposition.contains("report.pdf"));
    }

    @Test
    void downloadFallsBackToOctetStreamForBlankAndInvalidContentTypes() {
        assertDownloadContentType("", MediaType.APPLICATION_OCTET_STREAM);
        assertDownloadContentType("://not-a-media-type", MediaType.APPLICATION_OCTET_STREAM);
        assertDownloadContentType(null, MediaType.APPLICATION_OCTET_STREAM);
    }

    private void assertDownloadContentType(String contentType, MediaType expected) {
        StoredChatFile metadata = stored(contentType);
        Resource resource = new ByteArrayResource(new byte[]{1});
        when(files.download(org.mockito.ArgumentMatchers.any(ChatFilePrincipal.class), eq(FILE_ID.toString())))
                .thenReturn(new ChatFileService.Download(metadata, resource));

        ResponseEntity<Resource> response = controller.download(jwt(), FILE_ID.toString());

        assertEquals(expected, response.getHeaders().getContentType());
    }

    private StoredChatFile stored(String contentType) {
        return new StoredChatFile(
                FILE_ID,
                ROOM_ID,
                USER_ID,
                "report.pdf",
                contentType,
                123,
                "a".repeat(64),
                StoredChatFile.AvStatus.CLEAN,
                null,
                CREATED_AT);
    }

    private Jwt jwt() {
        Instant now = Instant.parse("2026-10-23T07:00:00Z");
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER_ID.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .claim("permissions", List.of(
                        ChatFilePrincipal.READ,
                        ChatFilePrincipal.WRITE,
                        ChatFilePrincipal.SEND_FILES))
                .build();
    }
}
