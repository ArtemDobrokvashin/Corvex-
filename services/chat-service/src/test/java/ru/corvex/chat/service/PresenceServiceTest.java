package ru.corvex.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PresenceServiceTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SESSIONS_KEY = "chat:presence:sessions:" + USER_ID;
    private static final String MANUAL_KEY = "chat:presence:manual:" + USER_ID;

    private StringRedisTemplate redis;
    private SetOperations<String, String> setOperations;
    private ValueOperations<String, String> valueOperations;
    private PresenceService presence;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        setOperations = mock(SetOperations.class);
        valueOperations = mock(ValueOperations.class);
        when(redis.opsForSet()).thenReturn(setOperations);
        when(redis.opsForValue()).thenReturn(valueOperations);
        presence = new PresenceService(redis);
    }

    @Test
    void heartbeatStoresSessionWithTtlAndLastSeen() {
        when(setOperations.add(SESSIONS_KEY, "ws-1")).thenReturn(1L);
        when(redis.expire(eq(SESSIONS_KEY), any(Duration.class))).thenReturn(true);

        presence.heartbeat(USER_ID, "ws-1");

        verify(setOperations).add(SESSIONS_KEY, "ws-1");
        verify(redis).expire(eq(SESSIONS_KEY), any(Duration.class));
        verify(valueOperations).set(eq("chat:presence:last-seen:" + USER_ID), any(String.class), any(Duration.class));
    }

    @Test
    void statusUsesManualDndWhenSessionExists() {
        when(setOperations.size(SESSIONS_KEY)).thenReturn(1L);
        when(valueOperations.get(MANUAL_KEY)).thenReturn("DND");

        assertEquals(PresenceService.Status.DND, presence.status(USER_ID));
    }

    @Test
    void statusIsOfflineWithoutSessionsEvenIfManualStatusRemains() {
        when(setOperations.size(SESSIONS_KEY)).thenReturn(0L);

        assertEquals(PresenceService.Status.OFFLINE, presence.status(USER_ID));
    }

    @Test
    void redisFailureDegradesPresenceToOffline() {
        when(setOperations.size(SESSIONS_KEY)).thenThrow(new IllegalStateException("redis down"));

        assertEquals(PresenceService.Status.OFFLINE, presence.status(USER_ID));
    }
}
