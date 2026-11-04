package ru.corvex.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.api.dto.AlertDtos.AlertRuntimeStatus;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsRequest;
import ru.corvex.admin.api.dto.AlertDtos.AlertSettingsView;
import ru.corvex.admin.api.dto.AlertDtos.RelayConfig;
import ru.corvex.admin.service.AlertConfigRepository.Config;
import ru.corvex.admin.service.AlertConfigRepository.Stored;
import ru.corvex.admin.service.AuditService.Actor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertSettingsServiceTest {

    private static final Actor ACTOR = new Actor(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), "root", "192.0.2.5");

    private AlertConfigRepository repository;
    private AuditService audit;
    private AlertSecretCipher cipher;
    private MockEnvironment env;
    private AlertSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlertConfigRepository.class);
        audit = mock(AuditService.class);
        cipher = mock(AlertSecretCipher.class);
        env = new MockEnvironment();
        when(repository.find()).thenReturn(Optional.empty());
        service = new AlertSettingsService(repository, audit, cipher, env);
    }

    @Test
    void environmentDefaultsAreEffectiveWithoutCreatingRegistryRowsOrExposingSecrets() {
        env.setProperty("CORVEX_ALERT_REPEAT_INTERVAL", "2h");
        env.setProperty("CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL", "15m");
        env.setProperty("CORVEX_ALERT_TELEGRAM_ENABLED", "true");
        env.setProperty("CORVEX_ALERT_TELEGRAM_CHAT_ID", "-100");
        env.setProperty("CORVEX_ALERT_TELEGRAM_BOT_TOKEN", "env-secret");

        AlertSettingsView view = service.settings();
        RelayConfig relay = service.relayConfig();

        assertEquals(0, view.revision());
        assertEquals("ENVIRONMENT", view.source());
        assertNull(view.updatedAt());
        assertEquals(120, view.repeatNormalMinutes());
        assertEquals(15, view.repeatCriticalMinutes());
        assertEquals(new ru.corvex.admin.api.dto.AlertDtos.SecretState(true, "ENVIRONMENT"),
                view.telegramBotToken());
        assertEquals("env-secret", relay.telegramBotToken());
        assertEquals(7_200, relay.repeatNormalSeconds());
        assertEquals(900, relay.repeatCriticalSeconds());
        assertEquals(5, relay.categories().size());
        verify(repository, never()).save(any(), any());
    }

    @Test
    void replaceEncryptsSecretsIncrementsRevisionAndAuditsOnlySecretNames() throws Exception {
        RequestBuilder builder = new RequestBuilder();
        builder.telegramEnabled = true;
        builder.telegramChatId = "-100123";
        builder.telegramBotToken = "telegram-secret";
        when(cipher.encrypt("telegram-secret")).thenReturn("encrypted-telegram");
        when(repository.save(any(), any())).thenAnswer(invocation -> new Stored(
                invocation.getArgument(0), 7L, Instant.parse("2026-11-04T10:00:00Z")));

        AlertSettingsView view = service.replace(builder.build(), ACTOR);

        ArgumentCaptor<Config> saved = ArgumentCaptor.forClass(Config.class);
        verify(repository).save(saved.capture(), org.mockito.ArgumentMatchers.eq(ACTOR));
        assertEquals("encrypted-telegram", saved.getValue().telegramBotTokenEnc());
        assertFalse(new ObjectMapper().writeValueAsString(saved.getValue()).contains("telegram-secret"));
        assertEquals(7, view.revision());
        assertEquals("DATABASE", view.source());
        assertEquals(new ru.corvex.admin.api.dto.AlertDtos.SecretState(true, "UI"),
                view.telegramBotToken());
        assertFalse(new ObjectMapper().writeValueAsString(view).contains("telegram-secret"));
        verify(audit).record(org.mockito.ArgumentMatchers.eq(ACTOR),
                org.mockito.ArgumentMatchers.eq("ALERT_SETTINGS_CHANGED"),
                org.mockito.ArgumentMatchers.eq("alert_runtime_config"),
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.argThat(details ->
                        details.get("changedSecrets").toString().contains("telegramBotToken")
                                && !details.toString().contains("telegram-secret")));

        when(repository.find()).thenReturn(Optional.of(new Stored(
                saved.getValue(), 7L, Instant.parse("2026-11-04T10:00:00Z"))));
        when(cipher.decrypt("encrypted-telegram")).thenReturn("telegram-secret");
        assertEquals("telegram-secret", service.relayConfig().telegramBotToken());
        assertEquals("admin@company.local", service.notificationPolicy().internalRecipients());
    }

    @Test
    void blankSecretKeepsCiphertextAndClearFallsBackToEnvironment() {
        RequestBuilder initial = new RequestBuilder();
        initial.telegramEnabled = true;
        initial.telegramChatId = "-100";
        initial.telegramBotToken = "ui-secret";
        when(cipher.encrypt("ui-secret")).thenReturn("encrypted-ui");
        when(repository.save(any(), any())).thenAnswer(invocation -> new Stored(
                invocation.getArgument(0), 1L, Instant.now()));
        service.replace(initial.build(), ACTOR);
        ArgumentCaptor<Config> first = ArgumentCaptor.forClass(Config.class);
        verify(repository).save(first.capture(), any());

        when(repository.find()).thenReturn(Optional.of(new Stored(first.getValue(), 1L, Instant.now())));
        when(cipher.decrypt("encrypted-ui")).thenReturn("ui-secret");
        RequestBuilder keep = new RequestBuilder();
        keep.telegramEnabled = true;
        keep.telegramChatId = "-100";
        service.replace(keep.build(), ACTOR);
        ArgumentCaptor<Config> kept = ArgumentCaptor.forClass(Config.class);
        verify(repository, org.mockito.Mockito.times(2)).save(kept.capture(), any());
        assertEquals("encrypted-ui", kept.getValue().telegramBotTokenEnc());

        env.setProperty("CORVEX_ALERT_TELEGRAM_BOT_TOKEN", "env-fallback");
        RequestBuilder clear = new RequestBuilder();
        clear.telegramEnabled = true;
        clear.telegramChatId = "-100";
        clear.clearTelegramBotToken = true;
        AlertSettingsView view = service.replace(clear.build(), ACTOR);
        assertEquals("ENVIRONMENT", view.telegramBotToken().source());
    }

    @Test
    void invalidPoliciesAreRejectedBeforeAnyWrite() {
        RequestBuilder badRepeat = new RequestBuilder();
        badRepeat.repeatNormalMinutes = 0;
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ApiException.class,
                () -> service.replace(badRepeat.build(), ACTOR)).status());

        RequestBuilder badCategories = new RequestBuilder();
        badCategories.categories = Map.of("ServiceDown", true);
        assertThrows(ApiException.class, () -> service.replace(badCategories.build(), ACTOR));

        RequestBuilder badTelegram = new RequestBuilder();
        badTelegram.telegramEnabled = true;
        assertThrows(ApiException.class, () -> service.replace(badTelegram.build(), ACTOR));

        RequestBuilder badMax = new RequestBuilder();
        badMax.maxEnabled = true;
        badMax.maxBotToken = "secret";
        badMax.maxChatId = "100";
        badMax.maxUserId = "200";
        when(cipher.encrypt("secret")).thenReturn("enc");
        when(cipher.decrypt("enc")).thenReturn("secret");
        assertThrows(ApiException.class, () -> service.replace(badMax.build(), ACTOR));

        RequestBuilder insecureSmtp = new RequestBuilder();
        insecureSmtp.externalSmtpTlsMode = "plain";
        assertThrows(ApiException.class, () -> service.replace(insecureSmtp.build(), ACTOR));
        verify(repository, never()).save(any(), any());
    }

    @Test
    void runtimeStatusConfirmsOnlyMatchingConsumerRevisions() throws IOException {
        HttpServer notification = startServer("/internal/alerts/runtime-status", 200, """
                {"revision":0,"source":"ADMIN","lastSuccessAt":"2026-11-04T10:00:00Z",
                 "internalEmailEnabled":true,"internalRecipients":"admin@company.local"}
                """);
        HttpServer relay = startServer("/healthz", 200, """
                {"runtime":{"revision":0,"source":"ADMIN","refresh":"SUCCESS",
                 "updatedAt":"2026-11-04T10:00:01Z"}}
                """);
        try {
            env.setProperty("CORVEX_NOTIFICATION_SERVICE_URL", baseUrl(notification));
            env.setProperty("CORVEX_ALERT_RELAY_URL", baseUrl(relay));

            AlertRuntimeStatus status = service.runtimeStatus();

            assertEquals(0, status.revision());
            assertEquals("APPLIED", status.consumers().get(0).status());
            assertEquals("APPLIED", status.consumers().get(1).status());
            assertEquals(Instant.parse("2026-11-04T10:00:00Z"),
                    status.consumers().get(0).updatedAt());
        } finally {
            notification.stop(0);
            relay.stop(0);
        }
    }

    @Test
    void runtimeStatusDistinguishesPendingAndUnavailableConsumers() throws IOException {
        HttpServer notification = startServer("/internal/alerts/runtime-status", 200, """
                {"revision":1,"source":"CACHE","lastSuccessAt":null,
                 "internalEmailEnabled":true,"internalRecipients":"admin@company.local"}
                """);
        try {
            env.setProperty("CORVEX_NOTIFICATION_SERVICE_URL", baseUrl(notification));
            env.setProperty("CORVEX_ALERT_RELAY_URL", baseUrl(notification)); // /healthz -> 404

            AlertRuntimeStatus status = service.runtimeStatus();

            assertEquals("PENDING", status.consumers().get(0).status());
            assertEquals(1, status.consumers().get(0).revision());
            assertEquals("UNAVAILABLE", status.consumers().get(1).status());
            assertNull(status.consumers().get(1).revision());
        } finally {
            notification.stop(0);
        }
    }

    @Test
    void malformedEnvironmentProducesHonestServiceUnavailable() {
        env.setProperty("CORVEX_ALERT_EXTERNAL_SMTP_PORT", "not-a-port");

        ApiException error = assertThrows(ApiException.class, service::settings);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
    }

    @Test
    void missingEncryptionKeyProducesHonestServiceUnavailable() {
        RequestBuilder request = new RequestBuilder();
        request.telegramBotToken = "secret";
        when(cipher.encrypt("secret")).thenThrow(new IllegalStateException("encryption key missing"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(request.build(), ACTOR));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        verify(repository, never()).save(any(), any());
    }

    private static HttpServer startServer(String path, int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static class RequestBuilder {
        int repeatNormalMinutes = 240;
        int repeatCriticalMinutes = 30;
        Map<String, Boolean> categories = categories();
        boolean internalEmailEnabled = true;
        String internalRecipients = "admin@company.local";
        String internalFrom = "alerts@company.local";
        boolean externalEmailEnabled;
        String externalEmailRecipients = "";
        String externalSmtpHost = "";
        int externalSmtpPort = 587;
        String externalSmtpTlsMode = "starttls";
        String externalSmtpUsername = "";
        String externalSmtpPassword;
        boolean clearExternalSmtpPassword;
        String externalSmtpFrom = "";
        boolean telegramEnabled;
        String telegramChatId = "";
        String telegramBotToken;
        boolean clearTelegramBotToken;
        boolean maxEnabled;
        String maxChatId = "";
        String maxUserId = "";
        String maxBotToken;
        boolean clearMaxBotToken;

        AlertSettingsRequest build() {
            return new AlertSettingsRequest(repeatNormalMinutes, repeatCriticalMinutes, categories,
                    internalEmailEnabled, internalRecipients, internalFrom,
                    externalEmailEnabled, externalEmailRecipients, externalSmtpHost, externalSmtpPort,
                    externalSmtpTlsMode, externalSmtpUsername, externalSmtpPassword,
                    clearExternalSmtpPassword, externalSmtpFrom,
                    telegramEnabled, telegramChatId, telegramBotToken, clearTelegramBotToken,
                    maxEnabled, maxChatId, maxUserId, maxBotToken, clearMaxBotToken);
        }
    }

    private static Map<String, Boolean> categories() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        AlertSettingsService.CATEGORY_CODES.forEach(code -> result.put(code, true));
        return result;
    }
}
