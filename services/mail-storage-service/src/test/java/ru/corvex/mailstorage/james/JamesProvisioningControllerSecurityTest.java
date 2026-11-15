package ru.corvex.mailstorage.james;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import ru.corvex.mailstorage.security.StorageSecurityConfig;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mocked JWT; does not verify a real JWT signature or enable WebAdmin. */
@WebMvcTest(controllers = JamesProvisioningController.class, properties = "corvex.james.provision.enabled=true")
@ContextConfiguration(classes = {JamesProvisioningControllerSecurityTest.TestApplication.class,
        StorageSecurityConfig.class, JamesProvisioningController.class})
class JamesProvisioningControllerSecurityTest {
    @SpringBootConfiguration @EnableAutoConfiguration static class TestApplication { }
    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;
    @MockBean JamesProvisioningAccess access;

    @Test void rejectsAnonymousAndUnauthorizedUsers() throws Exception {
        String path = "/api/v1/mail/james/admin/provisioning/users/" + UUID.randomUUID();
        mvc.perform(post(path)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("MAIL_READ_OWN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(access);
    }

    @Test void uncertainOutcomeReturnsOperationIdWithoutPrivateData() throws Exception {
        UUID owner = UUID.randomUUID(), operation = UUID.randomUUID();
        when(access.create(any(Authentication.class), eq(owner))).thenThrow(new JamesProvisioningUncertainException(operation));
        mvc.perform(post("/api/v1/mail/james/admin/provisioning/users/" + owner)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ADMIN_USERS_MANAGE"))))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.operationId").value(operation.toString()))
                .andExpect(jsonPath("$.state").value("uncertain"))
                .andExpect(jsonPath("$.login").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test void boundMeansDisabledAndNoOtherIdentityIsReturned() throws Exception {
        UUID operation = UUID.randomUUID(), owner = UUID.randomUUID();
        when(access.status(any(Authentication.class), eq(operation)))
                .thenReturn(new JamesProvisioningJournal.OperationStatus(operation, owner, "bound"));
        mvc.perform(get("/api/v1/mail/james/admin/provisioning/operations/" + operation)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ADMIN_USERS_MANAGE"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("bound-disabled"))
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test void revokedAccessFailsAndDetailIsNotReturned() throws Exception {
        UUID operation = UUID.randomUUID();
        when(access.status(any(Authentication.class), eq(operation))).thenThrow(new SecurityException("secret"));
        mvc.perform(get("/api/v1/mail/james/admin/provisioning/operations/" + operation)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ADMIN_USERS_MANAGE"))))
                .andExpect(status().isForbidden());
    }
}
