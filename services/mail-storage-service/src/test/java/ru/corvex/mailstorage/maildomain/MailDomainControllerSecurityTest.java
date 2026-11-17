package ru.corvex.mailstorage.maildomain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import ru.corvex.mailstorage.security.StorageSecurityConfig;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Мок-JWT; реальный WebAdmin James и DNS не используются. */
@WebMvcTest(controllers = MailDomainController.class)
@ContextConfiguration(classes = {MailDomainControllerSecurityTest.TestApplication.class,
        StorageSecurityConfig.class, MailDomainController.class})
class MailDomainControllerSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }

    @Autowired MockMvc mvc;
    @MockBean JwtDecoder decoder;
    @MockBean MailDomainService service;

    private static final UUID ADMIN_ID = UUID.fromString("8f14e45f-ceea-4e0f-9d3a-0b1f5c7d2e11");

    /**
     * Subject обязан быть UUID: контроллер пишет его в аудит и отвечает 401 на строку.
     * По умолчанию jwt() подставляет subject "user", из-за чего POST-тесты падали.
     */
    private static JwtRequestPostProcessor mailPolicyAdmin() {
        return jwt().jwt(token -> token.subject(ADMIN_ID.toString()))
                .authorities(new SimpleGrantedAuthority("ADMIN_MAIL_POLICY"));
    }

    private static MailDomainService.View view(String state, boolean receiving) {
        return new MailDomainService.View(UUID.randomUUID(), "company.local", true, state, null, true, true,
                "STATIC", true, Instant.parse("2026-10-19T10:00:00Z"), true, receiving, false, null,
                List.of(), List.of());
    }

    @Test void rejectsAnonymousAndUnauthorizedAdministrators() throws Exception {
        mvc.perform(get("/api/v1/mail/admin/domains")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/mail/admin/domains")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ADMIN_USERS_MANAGE"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void listsDomainsForMailPolicyAdministrator() throws Exception {
        when(service.list()).thenReturn(List.of(view("CREATED", true)));
        mvc.perform(get("/api/v1/mail/admin/domains")
                        .with(mailPolicyAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].domain").value("company.local"))
                .andExpect(jsonPath("$[0].receivingReady").value(true));
    }

    @Test void uncertainJamesOutcomeIsAcceptedNotOk() throws Exception {
        when(service.addToJames(eq("company.local"), any()))
                .thenReturn(new MailDomainService.JamesAttempt(view("UNCERTAIN", false), true));
        mvc.perform(post("/api/v1/mail/admin/domains/company.local/james")
                        .with(mailPolicyAdmin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jamesState").value("UNCERTAIN"));
    }

    @Test void confirmedJamesOutcomeIsOk() throws Exception {
        when(service.addToJames(eq("company.local"), any()))
                .thenReturn(new MailDomainService.JamesAttempt(view("CREATED", false), false));
        mvc.perform(post("/api/v1/mail/admin/domains/company.local/james")
                        .with(mailPolicyAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jamesState").value("CREATED"));
    }

    @Test void blankDomainIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/mail/admin/domains")
                        .contentType("application/json").content("{\"domain\":\"  \"}")
                        .with(mailPolicyAdmin()))
                .andExpect(status().isBadRequest());
        verify(service, never()).register(any(), any());
    }

    @Test void dnsInstructionsAreReadable() throws Exception {
        when(service.dnsInstructions("company.local")).thenReturn(List.of(
                new MailDomainService.DnsInstruction("MX", "@", "mail.company.local", 10, "приём")));
        mvc.perform(get("/api/v1/mail/admin/domains/company.local/dns")
                        .with(mailPolicyAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("MX"))
                .andExpect(jsonPath("$[0].priority").value(10));
    }
}
