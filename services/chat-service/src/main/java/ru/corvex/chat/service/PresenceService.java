package ru.corvex.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Присутствие в Redis.
 *
 * <p>Активные STOMP/REST-сессии лежат в SET с коротким TTL, ручной AWAY/DND — отдельным ключом.
 * Redis здесь не источник истины для сообщений, поэтому все операции деградируют мягко:
 * при недоступности Redis API вернёт OFFLINE, но чат продолжит работать через PostgreSQL/STOMP.</p>
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private static final Duration SESSION_TTL = Duration.ofSeconds(120);
    private static final Duration MANUAL_TTL = Duration.ofHours(12);
    private static final Duration LAST_SEEN_TTL = Duration.ofDays(30);
    private static final String REST_SESSION = "rest";

    private final StringRedisTemplate redis;

    public PresenceService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public enum Status {
        ONLINE,
        AWAY,
        DND,
        OFFLINE
    }

    public void onConnect(UUID userId, String sessionId) {
        heartbeat(userId, sessionId);
    }

    public void onDisconnect(UUID userId, String sessionId) {
        if (userId == null) {
            return;
        }
        String session = normalizeSession(sessionId);
        safe(() -> {
            redis.opsForSet().remove(sessionsKey(userId), session);
            touchLastSeen(userId);
        });
    }

    public void heartbeat(UUID userId) {
        heartbeat(userId, REST_SESSION);
    }

    public void heartbeat(UUID userId, String sessionId) {
        if (userId == null) {
            return;
        }
        String session = normalizeSession(sessionId);
        safe(() -> {
            String key = sessionsKey(userId);
            redis.opsForSet().add(key, session);
            redis.expire(key, SESSION_TTL);
            touchLastSeen(userId);
        });
    }

    public Status setStatus(UUID userId, String rawStatus) {
        if (userId == null || rawStatus == null || rawStatus.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Не указан статус присутствия");
        }
        Status requested;
        try {
            requested = Status.valueOf(rawStatus.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Допустимые статусы присутствия: ONLINE, AWAY, DND");
        }
        if (requested == Status.OFFLINE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "OFFLINE выставляется автоматически при отключении");
        }
        heartbeat(userId);
        safe(() -> {
            if (requested == Status.ONLINE) {
                redis.delete(manualKey(userId));
            } else {
                redis.opsForValue().set(manualKey(userId), requested.name(), MANUAL_TTL);
            }
        });
        return status(userId);
    }

    public Status status(UUID userId) {
        if (userId == null) {
            return Status.OFFLINE;
        }
        Long sessions = safeValue(() -> redis.opsForSet().size(sessionsKey(userId)), 0L);
        if (sessions == null || sessions == 0) {
            return Status.OFFLINE;
        }
        String manual = safeValue(() -> redis.opsForValue().get(manualKey(userId)), null);
        if (manual == null || manual.isBlank()) {
            return Status.ONLINE;
        }
        try {
            Status parsed = Status.valueOf(manual.trim().toUpperCase());
            return parsed == Status.OFFLINE ? Status.ONLINE : parsed;
        } catch (IllegalArgumentException ignored) {
            return Status.ONLINE;
        }
    }

    public Map<UUID, String> statuses(Collection<UUID> userIds) {
        Map<UUID, String> result = new LinkedHashMap<>();
        if (userIds == null) {
            return result;
        }
        for (UUID userId : userIds) {
            if (userId != null) {
                result.put(userId, status(userId).name());
            }
        }
        return result;
    }

    public Optional<Instant> lastSeen(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        String value = safeValue(() -> redis.opsForValue().get(lastSeenKey(userId)), null);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(value));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void touchLastSeen(UUID userId) {
        redis.opsForValue().set(lastSeenKey(userId), Instant.now().toString(), LAST_SEEN_TTL);
    }

    private String normalizeSession(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? REST_SESSION : sessionId.trim();
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

    private void safe(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.debug("Presence Redis operation failed: {}", e.getMessage());
        }
    }

    private <T> T safeValue(Supplier<T> action, T fallback) {
        try {
            T value = action.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            log.debug("Presence Redis read failed: {}", e.getMessage());
            return fallback;
        }
    }
}
