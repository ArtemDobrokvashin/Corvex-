package ru.corvex.admin.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.corvex.admin.api.dto.AlertDtos.AlertRuntimeStatus;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsRequest;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsView;
import ru.corvex.admin.api.dto.AlertDtos.NotificationPolicy;
import ru.corvex.admin.api.dto.AlertDtos.RelayConfig;
import ru.corvex.admin.api.dto.AlertDtos.TelegramDiscoveryRequest;
import ru.corvex.admin.api.dto.AlertDtos.TelegramDiscoveryView;
import ru.corvex.admin.service.AlertSettingsService;
import ru.corvex.admin.service.AuditService.Actor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AlertSettingsControllerTest {

    private AlertSettingsService service;
    private AlertSettingsController controller;
    private InternalAlertSettingsController internal;

    @BeforeEach
    void setUp() {
        service = mock(AlertSettingsService.class);
        controller = new AlertSettingsController(service);
        internal = new InternalAlertSettingsController(service);
    }

    @Test
    void onlySuperAdminGroupCanReadOrReplaceAlertSettings() {
        AlertSettingsView view = mock(AlertSettingsView.class);
        AlertRuntimeStatus runtime = mock(AlertRuntimeStatus.class);
        AlertSettingsRequest request = mock(AlertSettingsRequest.class);
        TelegramDiscoveryRequest telegramRequest = mock(TelegramDiscoveryRequest.class);
        TelegramDiscoveryView telegramView = mock(TelegramDiscoveryView.class);
        when(service.settings()).thenReturn(view);
        when(service.runtimeStatus()).thenReturn(runtime);
        when(service.replace(eq(request), any())).thenReturn(view);
        when(service.discoverTelegram(telegramRequest)).thenReturn(telegramView);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Real-IP", "203.0.113.9");

        assertSame(view, controller.settings(jwt(List.of("SUPER_ADMIN"))));
        assertSame(runtime, controller.runtime(jwt(List.of("SUPER_ADMIN"))));
        assertSame(view, controller.replace(request, jwt(List.of("SUPER_ADMIN", "ADMIN")), http));
        assertSame(telegramView, controller.discoverTelegram(
                telegramRequest, jwt(List.of("SUPER_ADMIN"))));

        Jwt delegatedAdmin = jwt(List.of("ADMIN"));
        ApiException readError = assertThrows(ApiException.class,
                () -> controller.settings(delegatedAdmin));
        ApiException runtimeError = assertThrows(ApiException.class,
                () -> controller.runtime(delegatedAdmin));
        ApiException writeError = assertThrows(ApiException.class,
                () -> controller.replace(request, delegatedAdmin, http));
        ApiException discoveryError = assertThrows(ApiException.class,
                () -> controller.discoverTelegram(telegramRequest, delegatedAdmin));
        assertEquals(HttpStatus.FORBIDDEN, readError.status());
        assertEquals(HttpStatus.FORBIDDEN, runtimeError.status());
        assertEquals(HttpStatus.FORBIDDEN, writeError.status());
        assertEquals(HttpStatus.FORBIDDEN, discoveryError.status());
        verify(service).settings();
        verify(service).runtimeStatus();
        verify(service).discoverTelegram(telegramRequest);
        verify(service).replace(eq(request), org.mockito.ArgumentMatchers.argThat(actor ->
                actor.name().equals("root") && actor.ip().equals("203.0.113.9")));
    }

    @Test
    void nonSuperAdminNeverReachesService() {
        assertThrows(ApiException.class, () -> controller.settings(jwt(List.of("USER"))));
        assertThrows(ApiException.class, () -> controller.discoverTelegram(
                mock(TelegramDiscoveryRequest.class), jwt(List.of("USER"))));
        verifyNoInteractions(service);
    }

    @Test
    void internalEndpointsDelegateWithoutExposingPublicSecretState() {
        NotificationPolicy policy = mock(NotificationPolicy.class);
        RelayConfig relay = mock(RelayConfig.class);
        when(service.notificationPolicy()).thenReturn(policy);
        when(service.relayConfig()).thenReturn(relay);

        assertSame(policy, internal.policy());
        assertSame(relay, internal.relayConfig());
    }

    private static Jwt jwt(List<String> groups) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claim("preferred_username", "root")
                .claim("groups", groups)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
