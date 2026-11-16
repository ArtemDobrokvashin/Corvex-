package ru.corvex.chatfile.drive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Маршрутизация диска и правила выдачи файла.
 *
 * <p>Отдельно проверяется корневой путь {@code /api/v1/drive}: именно он отдавал 404
 * на живом стенде, потому что маршрут шлюза был объявлен только как {@code /api/v1/drive/**}.
 * Остальные тесты фиксируют глаголы и пути контракта, чтобы контроллер и клиент
 * не разъехались незаметно.</p>
 */
class DriveControllerTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NODE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SHARE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final DriveService drive = mock(DriveService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new DriveController(drive))
                // В standalone-режиме резолвер принципала не регистрируется сам.
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

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
    void driveRootIsMappedWithAndWithoutTrailingSlash() throws Exception {
        // Ровно этот путь отдавал 404 на живом стенде: список файлов идёт как
        // GET /api/v1/drive?view=files, без сегмента после drive.
        when(drive.list(eq(USER), isNull(), eq(DriveService.View.FILES), isNull()))
                .thenReturn(List.of(folder()));

        mvc.perform(get("/api/v1/drive").param("view", "files"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].name").value("Договоры"));
        mvc.perform(get("/api/v1/drive/").param("view", "files"))
                .andExpect(status().isOk());
    }

    @Test
    void trashViewAndUsageAreMapped() throws Exception {
        when(drive.list(eq(USER), isNull(), eq(DriveService.View.TRASH), eq("акт")))
                .thenReturn(List.of());
        when(drive.usage(USER)).thenReturn(new DriveService.Usage(1024, 5_368_709_120L));

        mvc.perform(get("/api/v1/drive").param("view", "trash").param("q", "акт"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/drive/usage"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.usedBytes").value(1024));
    }

    @Test
    void mutationsUseTheDocumentedVerbsAndPaths() throws Exception {
        when(drive.createFolder(USER, null, "Отчёты")).thenReturn(folder());
        when(drive.rename(eq(USER), eq(NODE), eq("Новое"))).thenReturn(folder());
        when(drive.move(eq(USER), eq(NODE), isNull())).thenReturn(folder());
        when(drive.share(eq(USER), eq(NODE), eq(7))).thenReturn(new DriveService.ShareCreated(
                new DriveShare(SHARE, NODE, USER, "a".repeat(64), Instant.now(), null, null),
                "token"));
        when(drive.shares(USER, NODE)).thenReturn(List.of());

        mvc.perform(post("/api/v1/drive/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null,\"name\":\"Отчёты\"}"))
                .andExpect(status().isCreated());
        mvc.perform(patch("/api/v1/drive/" + NODE + "/name")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Новое\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/drive/" + NODE + "/move")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":null}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/drive/" + NODE + "/trash")).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/drive/" + NODE + "/restore")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/drive/" + NODE)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/drive/" + NODE + "/shares")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/drive/" + NODE + "/shares")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expiresInDays\":7}"))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.path").value("/api/v1/drive/shared/token"));
        mvc.perform(delete("/api/v1/drive/shares/" + SHARE)).andExpect(status().isNoContent());

        verify(drive).trash(USER, NODE);
        verify(drive).purge(USER, NODE);
        verify(drive).revokeShare(USER, SHARE);
    }

    @Test
    void ownerDownloadIsAttachmentToo() throws Exception {
        DriveNode node = file();
        when(drive.download(USER, NODE)).thenReturn(new DriveService.Download(
                node, new ByteArrayResource("data".getBytes(StandardCharsets.UTF_8))));

        mvc.perform(get("/api/v1/drive/" + NODE + "/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void unknownOrRevokedTokenIsNotFound() throws Exception {
        when(drive.downloadByToken(anyString()))
                .thenThrow(new ResponseStatusException(NOT_FOUND, "Ссылка недействительна"));

        mvc.perform(get("/api/v1/drive/shared/gone")).andExpect(status().isNotFound());
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(USER.toString())
                .issuedAt(Instant.parse("2026-11-16T09:00:00Z"))
                .expiresAt(Instant.parse("2026-11-16T10:00:00Z"))
                .claim("permissions", List.of("CHAT_READ"))
                .build();
    }

    private static DriveNode folder() {
        Instant now = Instant.parse("2026-11-16T09:00:00Z");
        return new DriveNode(NODE, USER, null, DriveNode.Kind.FOLDER, "Договоры", 0,
                null, null, null, now, now, null);
    }

    private static DriveNode file() {
        Instant now = Instant.parse("2026-11-16T09:00:00Z");
        return new DriveNode(NODE, USER, null, DriveNode.Kind.FILE, "Счёт.pdf", 4,
                "application/pdf", "a".repeat(64), "CLEAN", now, now, null);
    }
}
