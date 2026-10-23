package ru.corvex.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    @Mock private StringRedisTemplate redis;
    @Mock private SetOperations<String, String> setOperations;
    @Mock private ValueOperations<String, String> valueOperations;

    private PresenceService service;

    @BeforeEach
    void setUp() {
        service = new PresenceService(redis);
        lenient().when(redis.opsForSet()).thenReturn(setOperations);
        lenient().when(redis.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void nullUserIsIgnoredOrReportedOfflineWithoutRedis() {
        service.onConnect(null, "session");
        service.onDisconnect(null, "session");
        service.heartbeat(null);
        service.heartbeat(null, "session");

        assertEquals(PresenceService.Status.OFFLINE, service.status(null));
        assertEquals(Optional.empty(), service.lastSeen(null));
        assertEquals(Map.of(), service.statuses(null));
        assertEquals(Map.of(), service.statuses(Arrays.asList(null, null)));
        verifyNoInteractions(redis, setOperations, valueOperations);
    }

    @Test
    void heartbeatStoresTrimmedSessionRefreshesTtlAndTouchesLastSeen() {
        service.onConnect(USER_ID, " session-1 ");

        verify(setOperations).add(sessionsKey(USER_ID), "session-1");
        verify(redis).expire(sessionsKey(USER_ID), Duration.ofSeconds(120));
        ArgumentCaptor<String> lastSeen = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(lastSeenKey(USER_ID)), lastSeen.capture(), eq(Duration.ofDays(30)));
        assertNotNull(Instant.parse(lastSeen.getValue()));
    }

    @Test
    void blankSessionFallsBackToRestAndDisconnectRemovesIt() {
        service.heartbeat(USER_ID);
        verify(setOperations).add(sessionsKey(USER_ID), "rest");

        service.onDisconnect(USER_ID, " ");
        verify(setOperations).remove(sessionsKey(USER_ID), "rest");
        verify(valueOperations, times(2))
                .set(eq(lastSeenKey(USER_ID)), anyString(), eq(Duration.ofDays(30)));
    }

    @Test
    void setStatusValidatesAndPersistsManualStatuses() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.setStatus(null, "AWAY")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.setStatus(USER_ID, " ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.setStatus(USER_ID, "BUSY")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> service.setStatus(USER_ID, "offline")).getStatusCode());

        when(setOperations.size(sessionsKey(USER_ID))).thenReturn(1L);
        when(valueOperations.get(manualKey(USER_ID))).thenReturn("AWAY", (String) null);

        assertEquals(PresenceService.Status.AWAY, service.setStatus(USER_ID, " away "));
        verify(valueOperations).set(manualKey(USER_ID), "AWAY", Duration.ofHours(12));

        assertEquals(PresenceService.Status.ONLINE, service.setStatus(USER_ID, "online"));
        verify(redis).delete(manualKey(USER_ID));
    }

    @Test
    void statusCombinesSessionPresenceWithManualValue() {
        when(setOperations.size(sessionsKey(USER_ID))).thenReturn(0L, 1L);
        assertEquals(PresenceService.Status.OFFLINE, service.status(USER_ID));

        when(valueOperations.get(manualKey(USER_ID)))
                .thenReturn(null, " ", "dnd", "OFFLINE", "not-a-status");
        assertEquals(PresenceService.Status.ONLINE, service.status(USER_ID));
        assertEquals(PresenceService.Status.ONLINE, service.status(USER_ID));
        assertEquals(PresenceService.Status.DND, service.status(USER_ID));
        assertEquals(PresenceService.Status.ONLINE, service.status(USER_ID));
        assertEquals(PresenceService.Status.ONLINE, service.status(USER_ID));
    }

    @Test
    void statusesSkipsNullIdsAndPreservesInputOrder() {
        when(setOperations.size(sessionsKey(USER_ID))).thenReturn(1L);
        when(setOperations.size(sessionsKey(OTHER_ID))).thenReturn(0L);
        when(valueOperations.get(manualKey(USER_ID))).thenReturn("AWAY");

        Map<UUID, String> result = service.statuses(Arrays.asList(USER_ID, null, OTHER_ID, USER_ID));

        assertEquals(List.of(USER_ID, OTHER_ID), List.copyOf(result.keySet()));
        assertEquals("AWAY", result.get(USER_ID));
        assertEquals("OFFLINE", result.get(OTHER_ID));
    }

    @Test
    void lastSeenParsesValidValuesAndIgnoresBrokenOnes() {
        Instant seen = Instant.parse("2026-10-20T09:30:00Z");
        when(valueOperations.get(lastSeenKey(USER_ID)))
                .thenReturn(seen.toString(), " ", "not-a-date", null);

        assertEquals(Optional.of(seen), service.lastSeen(USER_ID));
        assertEquals(Optional.empty(), service.lastSeen(USER_ID));
        assertEquals(Optional.empty(), service.lastSeen(USER_ID));
        assertEquals(Optional.empty(), service.lastSeen(USER_ID));
    }

    @Test
    void redisFailuresDegradeGracefully() {
        when(redis.opsForSet()).thenThrow(new IllegalStateException("redis down"));
        assertDoesNotThrow(() -> service.heartbeat(USER_ID, "session"));
        assertDoesNotThrow(() -> service.onDisconnect(USER_ID, "session"));
        assertEquals(PresenceService.Status.OFFLINE, service.status(USER_ID));

        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        assertEquals(Optional.empty(), service.lastSeen(USER_ID));

        PresenceService noRedis = new PresenceService(null);
        assertDoesNotThrow(() -> noRedis.heartbeat(USER_ID));
        assertEquals(PresenceService.Status.OFFLINE, noRedis.status(USER_ID));
        assertTrue(noRedis.lastSeen(USER_ID).isEmpty());
    }

    private String sessionsKey(UUID userId) {
        return "chat:presence:sessions:" + userId;
    }

    private String manualKey(UUID userId) {
        return "chat:presence:manual:" + userId;
    }

    private String lastSeenKey(UUID userId) {
        return "chat:presence:last-seen:" + userId;
    }
}
