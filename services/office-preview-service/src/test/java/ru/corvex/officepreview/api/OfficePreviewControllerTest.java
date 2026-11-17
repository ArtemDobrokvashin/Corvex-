package ru.corvex.officepreview.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.officepreview.security.InternalTokenGuard;
import ru.corvex.officepreview.service.OfficeConversionService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OfficePreviewControllerTest {

    @TempDir
    Path temp;

    private OfficeConversionService conversion;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        conversion = mock(OfficeConversionService.class);
        var controller = new OfficePreviewController(new InternalTokenGuard("secret"), conversion);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returnsPdfAndCacheHeader() throws Exception {
        Path pdf = temp.resolve("result.pdf");
        Files.writeString(pdf, "%PDF-1.4\n%%EOF\n");
        when(conversion.convert(eq("report.docx"), anyLong(), any()))
                .thenReturn(new OfficeConversionService.Conversion(pdf, true));

        mvc.perform(post("/internal/office-preview/convert")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(InternalTokenGuard.HEADER, "secret")
                        .header("X-Office-File-Name", encode("report.docx"))
                        .content("bytes"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Office-Cache", "hit"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        mvc.perform(post("/internal/office-preview/convert")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("X-Office-File-Name", encode("report.docx"))
                        .content("bytes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void failsClosedWhenTokenIsNotConfigured() throws Exception {
        var controller = new OfficePreviewController(new InternalTokenGuard(""), conversion);
        var unconfigured = MockMvcBuilders.standaloneSetup(controller).build();

        unconfigured.perform(post("/internal/office-preview/convert")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(InternalTokenGuard.HEADER, "anything")
                        .header("X-Office-File-Name", encode("report.docx"))
                        .content("bytes"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void passesThroughConverterStatus() throws Exception {
        when(conversion.convert(any(), anyLong(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "bad document"));

        mvc.perform(post("/internal/office-preview/convert")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(InternalTokenGuard.HEADER, "secret")
                        .header("X-Office-File-Name", encode("report.docx"))
                        .content("bytes"))
                .andExpect(status().isUnprocessableEntity());
    }

    private String encode(String name) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
    }
}
