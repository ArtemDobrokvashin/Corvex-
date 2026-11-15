package ru.corvex.mailstorage.james;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.security.StorageSecurityConfig;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Filter-chain and method-security checks; jwt() simulates authentication, not signature verification. */
@WebMvcTest(controllers = JamesPreviewController.class, properties = "corvex.james.read.enabled=true")
@ContextConfiguration(classes = {JamesPreviewSecurityTest.TestApplication.class, StorageSecurityConfig.class, JamesPreviewController.class})
class JamesPreviewSecurityTest {
    @SpringBootConfiguration @EnableAutoConfiguration static class TestApplication { }
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;
    @MockBean JamesOwnMailboxAccess access;

    @Test void deniesAnonymousAndWrongPermission() throws Exception {
        mvc.perform(get("/api/v1/mail/james/folders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/mail/james/folders").with(jwt().authorities(new SimpleGrantedAuthority("OTHER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(access);
    }

    @Test void authenticatedReadOnlyUserGetsOwnScopedResponse() throws Exception {
        UUID id = UUID.randomUUID();
        when(access.listOwnFolders(any(Authentication.class))).thenReturn(List.of(new MailDtos.FolderView(id, "Inbox", "INBOX", 1, 1)));
        mvc.perform(get("/api/v1/mail/james/folders").with(jwt().authorities(new SimpleGrantedAuthority("MAIL_READ_OWN"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$[0].id").value(id.toString()));
        verify(access).listOwnFolders(any(Authentication.class));
    }
}
