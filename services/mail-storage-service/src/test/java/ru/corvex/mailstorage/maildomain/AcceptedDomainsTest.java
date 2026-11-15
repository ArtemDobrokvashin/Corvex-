package ru.corvex.mailstorage.maildomain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcceptedDomainsTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private final MailDomainStore store = mock(MailDomainStore.class);

    private AcceptedDomains policy(List<String> registry) {
        when(store.deliveryDomains()).thenReturn(registry);
        return new AcceptedDomains(Set.of("Company.Local"), store, Duration.ofSeconds(30),
                Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test void staticDomainsWorkDuringARegistryOutage() {
        when(store.deliveryDomains()).thenThrow(new IllegalStateException("db down"));
        AcceptedDomains policy = new AcceptedDomains(Set.of("company.local"), store,
                Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(AcceptedDomains.Verdict.LOCAL, policy.classify("company.local"));
        assertEquals(AcceptedDomains.Verdict.UNKNOWN, policy.classify("new.example"));
    }

    @Test void confirmedRegistryDomainIsLocal() {
        AcceptedDomains policy = policy(List.of("new.example"));
        assertEquals(AcceptedDomains.Verdict.LOCAL, policy.classify("new.example"));
        assertEquals(AcceptedDomains.Verdict.FOREIGN, policy.classify("partner.example"));
        assertEquals(AcceptedDomains.Verdict.LOCAL, policy.classify("COMPANY.LOCAL"));
    }

    @Test void unknownDomainIsNotTreatedAsForeignWhenTheListIsUnavailable() {
        when(store.deliveryDomains()).thenThrow(new IllegalStateException("db down"));
        AcceptedDomains policy = new AcceptedDomains(Set.of(), store, Duration.ofSeconds(30),
                Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(AcceptedDomains.Verdict.UNKNOWN, policy.classify("company.local"));
    }

    @Test void failedLookupIsNotRepeatedForEveryMessage() {
        AtomicInteger calls = new AtomicInteger();
        when(store.deliveryDomains()).thenAnswer(invocation -> {
            calls.incrementAndGet();
            throw new IllegalStateException("db down");
        });
        AcceptedDomains policy = new AcceptedDomains(Set.of("company.local"), store,
                Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

        for (int i = 0; i < 20; i++) {
            policy.classify("other" + i + ".example");
        }
        assertEquals(1, calls.get(), "недоступная БД не должна опрашиваться на каждое письмо");
    }

    @Test void staleSnapshotStopsCountingAsEvidence() {
        // Часы двигаются вручную: сначала успешное чтение, затем БД падает.
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        when(store.deliveryDomains()).thenReturn(List.of("new.example"));
        AcceptedDomains policy = new AcceptedDomains(Set.of(), store, Duration.ofSeconds(30),
                Duration.ofMinutes(1), clock);
        assertEquals(AcceptedDomains.Verdict.LOCAL, policy.classify("new.example"));

        when(store.deliveryDomains()).thenThrow(new IllegalStateException("db down"));
        now.set(NOW.plus(Duration.ofMinutes(2)));
        assertEquals(AcceptedDomains.Verdict.UNKNOWN, policy.classify("new.example"),
                "устаревшее значение не должно доказывать, что домен локальный");

        when(store.deliveryDomains()).thenReturn(List.of("other.example"));
        now.set(NOW.plus(Duration.ofMinutes(3)));
        assertEquals(AcceptedDomains.Verdict.FOREIGN, policy.classify("new.example"),
                "после успешного обновления список снова доказателен");
    }

    @Test void sourceExplainsWhereTheDecisionCameFrom() {
        when(store.deliveryDomains()).thenReturn(List.of("new.example"));
        AcceptedDomains policy = new AcceptedDomains(Set.of("company.local"), store,
                Duration.ofSeconds(30), Duration.ofMinutes(5), Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(AcceptedDomains.Source.STATIC, policy.source("company.local"));
        assertEquals(AcceptedDomains.Source.DYNAMIC, policy.source("new.example"));
        assertEquals(AcceptedDomains.Source.ABSENT, policy.source("partner.example"));
    }

    @Test void missingEmailDomainIsForeign() {
        AcceptedDomains policy = policy(List.of());
        assertEquals(AcceptedDomains.Verdict.FOREIGN, policy.classify(""));
        assertEquals(AcceptedDomains.Verdict.FOREIGN, policy.classify(null));
        assertFalse(policy.isLocal("no-at-sign"));
    }

    @Test void staticOnlyModeDoesNotInventUnknownVerdicts() {
        AcceptedDomains policy = AcceptedDomains.staticOnly("company.local, branch.example");

        assertEquals(AcceptedDomains.Verdict.LOCAL, policy.classify("branch.example"));
        assertEquals(AcceptedDomains.Verdict.FOREIGN, policy.classify("partner.example"));
        assertEquals(AcceptedDomains.Source.ABSENT, policy.source("partner.example"));
    }
}
