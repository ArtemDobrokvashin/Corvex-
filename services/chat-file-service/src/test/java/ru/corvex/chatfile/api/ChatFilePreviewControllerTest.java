package ru.corvex.chatfile.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.chatfile.client.OfficePreviewClient;
import ru.corvex.chatfile.domain.StoredChatFile;
import ru.corvex.chatfile.service.ChatFileService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatFilePreviewControllerTest {

    private static final UUID FILE_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ROOM_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private ChatFileService files;
    private OfficePreviewClient preview;
    private ChatFilePreviewController controller;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        files = mock(ChatFileService.class);
        preview = mock(OfficePreviewClient.class);
        controller = new ChatFilePreviewController(files, preview);
        jwt = Jwt.withTokenValue("token").header("alg", "none").subject(USER_ID.toString())
                .claim("permissions", java.util.List.of("CHAT_READ")).build();
    }

    @Test
    void streamsPdfWithInlineHeaders() throws Exception {
        StoredChatFile metadata = new StoredChatFile(FILE_ID, ROOM_ID, USER_ID, "plan.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 4,
                "a".repeat(64), StoredChatFile.AvStatus.CLEAN, null, Instant.now());
        when(files.download(any(), eq(FILE_ID.toString())))
                .thenReturn(new ChatFileService.Download(metadata,
                        new ByteArrayResource("xlsx".getBytes(StandardCharsets.UTF_8))));
        when(preview.enabled()).thenReturn(true);
        when(preview.convert(any(InputStream.class), eq(4L), eq("plan.xlsx")))
                .thenReturn(new ByteArrayInputStream("%PDF-1.4".getBytes(StandardCharsets.UTF_8)));

        var response = controller.preview(jwt, FILE_ID.toString());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("%PDF-1.4", new String(response.getBody().getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
    }

    @Test
    void lockedFileIsRejectedBeforeConverter() {
        when(files.download(any(), eq(FILE_ID.toString())))
                .thenThrow(new ResponseStatusException(HttpStatus.LOCKED, "Файл не проверен"));

        var error = assertThrows(ResponseStatusException.class, () -> controller.preview(jwt, FILE_ID.toString()));
        assertEquals(423, error.getStatusCode().value());
    }

    @Test
    void disabledPreviewReturnsNotImplemented() throws Exception {
        StoredChatFile metadata = new StoredChatFile(FILE_ID, ROOM_ID, USER_ID, "plan.xlsx",
                "application/octet-stream", 1, "a".repeat(64),
                StoredChatFile.AvStatus.CLEAN, null, Instant.now());
        when(files.download(any(), eq(FILE_ID.toString())))
                .thenReturn(new ChatFileService.Download(metadata, new ByteArrayResource(new byte[]{1})));
        when(preview.enabled()).thenReturn(false);

        var error = assertThrows(ResponseStatusException.class, () -> controller.preview(jwt, FILE_ID.toString()));
        assertEquals(501, error.getStatusCode().value());
    }
}
