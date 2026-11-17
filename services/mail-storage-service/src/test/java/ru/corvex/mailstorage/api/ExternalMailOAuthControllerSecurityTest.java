package ru.corvex.mailstorage.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import ru.corvex.mailstorage.api.dto.ExternalMailOAuthDtos;
import ru.corvex.mailstorage.external.ExternalMailOAuthService;
import ru.corvex.mailstorage.security.StorageSecurityConfig;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Границы доступа OAuth-входа: callback открыт (его вызывает браузер после редиректа),
 * старт входа — только своему пользователю или администратору с правом на внешние ящики.
 */
@WebMvcTest(controllers = ExternalMailOAuthController.class)
@ContextConfiguration(classes = {ExternalMailOAuthControllerSecurityTest.TestApplication.class,
        StorageSecurityConfig.class, ExternalMailOAuthController.class})
class ExternalMailOAuthControllerSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;
    @MockBean ExternalMailOAuthService service;

    private static JwtRequestPostProcessor user(String authority) {
        return jwt().jwt(token -> token.subject(USER_ID.toString())
                        .claim("permissions", List.of(authority)))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    @Test
    void callbackWorksWithoutTokenAndRedirectsToApplication() throws Exception {
        when(service.callback("code-1", "state-1", null))
                .thenReturn("https://mail.example.test/mail?oauth=connected&connection=abc");

        mvc.perform(get("/api/v1/mail/external-accounts/oauth/callback")
                        .param("code", "code-1").param("state", "state-1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://mail.example.test/mail?oauth=connected&connection=abc"));
    }

    @Test
    void callbackAcceptsProviderErrorWithoutCode() throws Exception {
        when(service.callback(isNull(), eq("state-1"), eq("access_denied")))
                .thenReturn("https://mail.example.test/mail?oauth=error&reason=access_denied");

        mvc.perform(get("/api/v1/mail/external-accounts/oauth/callback")
                        .param("state", "state-1").param("error", "access_denied"))
                .andExpect(status().isFound());
    }

    @Test
    void authorizeAndProvidersRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/mail/external-accounts/oauth/providers"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mail/external-accounts/oauth/authorize")
                        .contentType("application/json").content("{\"provider\":\"GOOGLE\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void authorizeRequiresExternalMailPermission() throws Exception {
        mvc.perform(post("/api/v1/mail/external-accounts/oauth/authorize")
                        .contentType("application/json").content("{\"provider\":\"GOOGLE\"}")
                        .with(user("MAIL_READ_OWN")))
                .andExpect(status().isForbidden());
        verify(service, never()).start(any(), any(), any());
    }

    @Test
    void authorizePassesUuidSubjectAndPermissions() throws Exception {
        when(service.start(eq(USER_ID), eq(Set.of("MAIL_EXTERNAL_ACCOUNTS")), any()))
                .thenReturn(new ExternalMailOAuthDtos.AuthorizeResponse(
                        "https://accounts.google.com/o/oauth2/v2/auth?state=s", "s", Instant.now().plusSeconds(600)));

        mvc.perform(post("/api/v1/mail/external-accounts/oauth/authorize")
                        .contentType("application/json")
                        .content("{\"provider\":\"GOOGLE\",\"returnPath\":\"/mail\"}")
                        .with(user("MAIL_EXTERNAL_ACCOUNTS")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("s"));
    }

    @Test
    void providersListIsAvailableToOrdinaryUser() throws Exception {
        when(service.providers()).thenReturn(List.of(
                new ExternalMailOAuthDtos.ProviderView("GOOGLE", "Google", false, "Не настроено")));

        mvc.perform(get("/api/v1/mail/external-accounts/oauth/providers")
                        .with(user("MAIL_READ_OWN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].provider").value("GOOGLE"))
                .andExpect(jsonPath("$[0].enabled").value(false));
    }
}
