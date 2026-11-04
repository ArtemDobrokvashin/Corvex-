package ru.corvex.notification;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlertRuntimePolicyProviderTest {

    @Test
    void cachesSuccessfulPolicyAndKeepsItWhenAdminBecomesUnavailable() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
        AtomicInteger calls = new AtomicInteger();
        AlertRuntimePolicyProvider.Policy remote = policy(9);
        AlertRuntimePolicyProvider provider = new AlertRuntimePolicyProvider(() -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("offline");
            return remote;
        }, policy(0), clock);

        assertEquals(9, provider.current().revision());
        assertEquals(9, provider.current().revision());
        assertEquals(1, calls.get());
        clock.advanceSeconds(11);
        assertEquals(9, provider.current().revision());
        assertEquals("CACHE", provider.status().source());
        assertEquals(true, provider.status().internalEmailEnabled());
        assertEquals("admin@example.test", provider.status().internalRecipients());
        assertEquals(2, calls.get());
    }

    @Test
    void initialFailureAndInvalidRemotePolicyUseEnvironmentFallback() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
        AlertRuntimePolicyProvider unavailable = new AlertRuntimePolicyProvider(
                () -> { throw new IllegalStateException("offline"); }, policy(0), clock);
        assertEquals(0, unavailable.current().revision());
        assertEquals("ENVIRONMENT", unavailable.status().source());

        AlertRuntimePolicyProvider invalid = new AlertRuntimePolicyProvider(
                () -> new AlertRuntimePolicyProvider.Policy(5, null, 0, 30, Map.of(), true, "a@b", "c@d"),
                policy(0), clock);
        assertEquals(0, invalid.current().revision());
    }

    @Test
    void statusEndpointRefreshesAndExposesOnlyNonSecretEffectiveDeliveryFields() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-04T10:00:00Z"));
        AlertRuntimePolicyProvider provider = new AlertRuntimePolicyProvider(
                () -> policy(7), policy(0), clock);
        AlertRuntimeStatusController controller = new AlertRuntimeStatusController(provider);

        AlertRuntimePolicyProvider.Status status = controller.status();

        assertEquals(7, status.revision());
        assertEquals("ADMIN", status.source());
        assertEquals(true, status.internalEmailEnabled());
        assertEquals("admin@example.test", status.internalRecipients());
    }

    @Test
    void parsesSupportedFallbackDurationsAndRejectsInvalidValues() {
        assertEquals(30, AlertRuntimePolicyProvider.durationSeconds("30s"));
        assertEquals(7_200, AlertRuntimePolicyProvider.durationSeconds("2h"));
        assertEquals(86_400, AlertRuntimePolicyProvider.durationSeconds("1d"));
        assertThrows(IllegalArgumentException.class, () -> AlertRuntimePolicyProvider.durationSeconds("4 hours"));
        assertThrows(IllegalArgumentException.class, () -> AlertRuntimePolicyProvider.durationSeconds("0m"));
    }

    private static AlertRuntimePolicyProvider.Policy policy(long revision) {
        return new AlertRuntimePolicyProvider.Policy(revision, null, 14_400, 1_800,
                AlertRuntimePolicyProvider.defaultCategories(), true, "admin@example.test", "alerts@example.test");
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
