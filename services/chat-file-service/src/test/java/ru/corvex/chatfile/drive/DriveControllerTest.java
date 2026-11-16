package ru.corvex.chatfile.drive;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Публичная ссылка — единственный путь диска без JWT. Проверяем, что она отдаёт файл
 * принудительным attachment'ом, не даёт браузеру выполнить содержимое и не кэшируется.
 */
class DriveControllerTest {

    private final DriveService drive = mock(DriveService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DriveController(drive)).build();

    @Test
    void publicShareIsServedAsAttachmentWithoutCaching() throws Exception {
        DriveNode node = new DriveNode(UUID.randomUUID(), UUID.randomUUID(), null,
                DriveNode.Kind.FILE, "отчёт.html", 12, "text/html", "a".repeat(64), "CLEAN",
                Instant.now(), Instant.now(), null);
        when(drive.downloadByToken("token")).thenReturn(new DriveService.Download(
                node, new ByteArrayResource("<html></html>".getBytes(StandardCharsets.UTF_8))));

        mvc.perform(get("/api/v1/drive/shared/token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'none'; sandbox"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                        org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void unknownOrRevokedTokenIsNotFound() throws Exception {
        when(drive.downloadByToken(anyString()))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "Ссылка недействительна"));

        mvc.perform(get("/api/v1/drive/shared/gone")).andExpect(status().isNotFound());
    }
}
