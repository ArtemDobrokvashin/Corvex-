package ru.corvex.admin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import ru.corvex.admin.api.ApiException;
import ru.corvex.admin.api.dto.AdminDtos.AnalyticsResponse;
import ru.corvex.admin.api.dto.AdminDtos.BackupResponse;
import ru.corvex.admin.api.dto.AdminDtos.HealthResponse;
import ru.corvex.admin.client.AuthServiceClient;
import ru.corvex.admin.client.AuthServiceClient.RemoteStats;
import ru.corvex.admin.service.AuditService.Actor;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSystemServiceTest {

    private static final Actor ACTOR = new Actor(
            UUID.fromString("11111111-1111-4111-8111-111111111111"), "root", "192.0.2.7");

    private JdbcTemplate jdbc;
    private AuditService audit;
    private AuthServiceClient auth;
    private AdminSystemService service;
    private Map<String, String> storedSettings;
    private Map<String, String> updatedBy;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        auth = mock(AuthServiceClient.class);
        storedSettings = new LinkedHashMap<>();
        updatedBy = new HashMap<>();
        stubSettingsTable();
        service = new AdminSystemService(
                jdbc, audit, auth, "127.0.0.1", "invalid-user", "invalid-password");
    }

    @Test
    void settingsMergeStoredOverridesIntoDefaults() {
        storedSettings.put("mail.maxMessageSizeMb", "50");
        storedSettings.put("security.requireTotp", "true");

        Map<String, String> result = service.settings();

        assertEquals("50", result.get("mail.maxMessageSizeMb"));
        assertEquals("true", result.get("security.requireTotp"));
        assertEquals("5368709120", result.get("mail.defaultQuotaBytes"));
        assertEquals(7, result.size());
    }

    @Test
    void patchSettingsNormalizesEveryKnownTypeAndAuditsKeys() {
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put("mail.maxMessageSizeMb", " 42 ");
        patch.put("mail.defaultQuotaBytes", " 123456 ");
        patch.put("mail.spamThreshold", " 7.50 ");
        patch.put("mail.pop3.enabled", " FALSE ");
        patch.put("security.passwordMinLength", " 12 ");
        patch.put("security.requireTotp", " TRUE ");
        patch.put("chat.maxFileSizeMb", " 64 ");

        Map<String, String> result = service.patchSettings(patch, ACTOR);

        assertEquals("42", result.get("mail.maxMessageSizeMb"));
        assertEquals("123456", result.get("mail.defaultQuotaBytes"));
        assertEquals("7.5", result.get("mail.spamThreshold"));
        assertEquals("false", result.get("mail.pop3.enabled"));
        assertEquals("12", result.get("security.passwordMinLength"));
        assertEquals("true", result.get("security.requireTotp"));
        assertEquals("64", result.get("chat.maxFileSizeMb"));
        assertEquals(ACTOR.id().toString(), updatedBy.get("mail.maxMessageSizeMb"));
        verify(audit).record(eq(ACTOR), eq("SETTINGS_CHANGED"), eq("settings"), isNull(),
                eq(Map.of("keys", List.copyOf(patch.keySet()))));
    }

    @Test
    void patchSettingsUsesNullUpdatedByForSystemActor() {
        service.patchSettings(Map.of("chat.maxFileSizeMb", "10"), Actor.system());

        assertTrue(updatedBy.containsKey("chat.maxFileSizeMb"));
        assertNull(updatedBy.get("chat.maxFileSizeMb"));
    }

    @Test
    void patchSettingsRejectsEmptyUnknownAndNullValuesBeforeWriting() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.patchSettings(null, ACTOR)).status());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.patchSettings(Map.of(), ACTOR)).status());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                assertThrows(ApiException.class,
                        () -> service.patchSettings(Map.of("unknown.key", "1"), ACTOR)).status());

        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("mail.maxMessageSizeMb", null);
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> service.patchSettings(nullValue, ACTOR)).status());

        Map<String, String> partiallyValid = new LinkedHashMap<>();
        partiallyValid.put("mail.maxMessageSizeMb", "50");
        partiallyValid.put("unknown.key", "1");
        assertThrows(ApiException.class, () -> service.patchSettings(partiallyValid, ACTOR));
        assertFalse(storedSettings.containsKey("mail.maxMessageSizeMb"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void patchSettingsRejectsBlankMalformedAndOutOfRangeValues() {
        Map<String, String> invalid = new LinkedHashMap<>();
        invalid.put("mail.maxMessageSizeMb", "0");
        invalid.put("chat.maxFileSizeMb", "1025");
        invalid.put("mail.defaultQuotaBytes", "-1");
        invalid.put("mail.defaultQuotaBytes", "1000000000000001");
        invalid.put("mail.spamThreshold", "NaN");
        invalid.put("mail.spamThreshold", "100.1");
        invalid.put("security.passwordMinLength", "7");
        invalid.put("mail.pop3.enabled", "yes");
        invalid.put("security.requireTotp", " ");

        // Map сохраняет последнее значение для повторяющихся ключей; остальные кейсы
        // проверяются ниже по одному, чтобы каждый invalid branch был явным.
        invalid.forEach((key, value) -> assertThrows(ApiException.class,
                () -> service.patchSettings(Map.of(key, value), ACTOR), key));
        assertThrows(ApiException.class,
                () -> service.patchSettings(Map.of("mail.defaultQuotaBytes", "-1"), ACTOR));
        assertThrows(ApiException.class,
                () -> service.patchSettings(Map.of("mail.spamThreshold", "NaN"), ACTOR));
        assertThrows(ApiException.class,
                () -> service.patchSettings(Map.of("mail.spamThreshold", "Infinity"), ACTOR));
        assertThrows(ApiException.class,
                () -> service.patchSettings(Map.of("mail.maxMessageSizeMb", "abc"), ACTOR));
    }

    @Test
    void analyticsMapsRemoteStatsAndTimestamp() {
        when(auth.stats("Sales")).thenReturn(new RemoteStats(20, 15, 2, 3, 7L, 40L, "Sales"));
        Instant before = Instant.now();

        AnalyticsResponse result = service.analytics("OWN_UNIT", "Sales");

        assertEquals("OWN_UNIT", result.scope());
        assertEquals("Sales", result.department());
        assertEquals(20, result.usersTotal());
        assertEquals(15, result.usersActive());
        assertEquals(2, result.usersBlocked());
        assertEquals(3, result.usersDisabled());
        assertEquals(7L, result.loginsLast24h());
        assertEquals(40L, result.loginsLast7d());
        assertFalse(result.generatedAt().isBefore(before));
    }

    @Test
    void requestBackupAuditsAndExplainsHostScheduler() {
        BackupResponse result = service.requestBackup(ACTOR);

        assertEquals("REQUESTED", result.status());
        assertTrue(result.note().contains("backup.ps1"));
        verify(audit).record(ACTOR, "BACKUP_REQUESTED", "backup", null, Map.of());
    }

    @Test
    void healthReturnsAllProbesDiskAndUnknownQueueWhenRabbitManagementRejects() {
        HealthResponse result = service.health();

        assertEquals(12, result.services().size());
        assertEquals("web-gateway", result.services().get(0).name());
        assertEquals("notification-service", result.services().get(11).name());
        assertTrue(result.services().stream().allMatch(s ->
                Set.of("UP", "DEGRADED", "DOWN").contains(s.status())));
        assertTrue(result.diskFreeBytes() >= 0);
        assertEquals(-1, result.mailQueueDepth());
    }

    private void stubSettingsTable() {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            for (Map.Entry<String, String> entry : storedSettings.entrySet()) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getString("key")).thenReturn(entry.getKey());
                when(rs.getString("value")).thenReturn(entry.getValue());
                handler.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));

        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            Object[] values = args.length > 1 && args[1] instanceof Object[] array
                    ? array
                    : java.util.Arrays.copyOfRange(args, 1, args.length);
            String key = (String) values[0];
            storedSettings.put(key, (String) values[1]);
            updatedBy.put(key, (String) values[2]);
            return 1;
        });
    }
}
