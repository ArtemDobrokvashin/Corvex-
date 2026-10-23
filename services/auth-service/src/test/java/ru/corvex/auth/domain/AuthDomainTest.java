package ru.corvex.auth.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthDomainTest {

    @Test
    void userDefaultsAndLifecycleTimestamps() {
        User user = new User();
        assertEquals(User.Source.LOCAL, user.getSource());
        assertEquals(User.Status.ACTIVE, user.getStatus());
        assertEquals(5368709120L, user.getQuotaBytes());

        user.onCreate();
        Instant created = user.getCreatedAt();
        Instant updated = user.getUpdatedAt();
        assertNotNull(created);
        assertEquals(created, updated);

        user.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        user.onCreate();
        assertEquals(created, user.getCreatedAt());
        assertTrue(user.getUpdatedAt().isAfter(Instant.parse("2026-01-01T00:00:00Z")));

        Instant beforeUpdate = Instant.now();
        user.onUpdate();
        assertFalse(user.getUpdatedAt().isBefore(beforeUpdate));
    }

    @Test
    void refreshSessionLifecycleAndActivityWindow() {
        RefreshSession session = new RefreshSession();
        session.setExpiresAt(Instant.parse("2026-02-01T00:00:00Z"));
        session.onCreate();
        Instant created = session.getCreatedAt();
        assertNotNull(created);
        session.onCreate();
        assertEquals(created, session.getCreatedAt());

        assertTrue(session.isActiveAt(Instant.parse("2026-01-31T23:59:59Z")));
        assertFalse(session.isActiveAt(Instant.parse("2026-02-01T00:00:00Z")));
        session.setRevokedAt(Instant.parse("2026-01-15T00:00:00Z"));
        assertFalse(session.isActiveAt(Instant.parse("2026-01-20T00:00:00Z")));
    }

    @Test
    void appPasswordLifecycleDoesNotOverwriteCreationTime() {
        AppPassword password = new AppPassword();
        password.onCreate();
        Instant created = password.getCreatedAt();
        assertNotNull(created);
        password.onCreate();
        assertEquals(created, password.getCreatedAt());
    }

    @Test
    void loginAttemptStoresConstructorDataAndLifecycleTime() {
        LoginAttempt attempt = new LoginAttempt(
                "ivan", "192.0.2.10", true, LoginAttempt.REASON_OK);

        assertEquals("ivan", attempt.getUsername());
        assertEquals("192.0.2.10", attempt.getIpAddress());
        assertTrue(attempt.isSuccess());
        assertEquals("OK", attempt.getReason());
        attempt.onCreate();
        Instant created = attempt.getCreatedAt();
        assertNotNull(created);
        attempt.onCreate();
        assertEquals(created, attempt.getCreatedAt());
    }
}
