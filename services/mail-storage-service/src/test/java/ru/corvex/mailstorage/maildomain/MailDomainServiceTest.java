package ru.corvex.mailstorage.maildomain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.james.JamesDomainAdmin;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailDomainServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DOMAIN_ID = UUID.randomUUID();

    private final MailDomainStore store = mock(MailDomainStore.class);
    private final JamesDomainAdmin admin = mock(JamesDomainAdmin.class);

    private MailDomainService service(Optional<JamesDomainAdmin> james) {
        return new MailDomainService(store, james,
                new MailDomainVerifier(new EmptyDns(), List.of("mail.company.local"), "corvex1"),
                Optional.empty(), AcceptedDomains.staticOnly("company.local"),
                List.of("mail.company.local"), "corvex1",
                Duration.ofHours(1), Duration.ofMinutes(2), CLOCK);
    }

    private static MailDomainStore.Row row(String state, Instant requestedAt) {
        return new MailDomainStore.Row(DOMAIN_ID, "company.local", true, state, requestedAt, null,
                ACTOR, NOW.minusSeconds(600));
    }

    private static final class EmptyDns implements DnsRecordSource {
        @Override public List<String> mxHosts(String domain) { return List.of("mail.company.local"); }
        @Override public List<String> txt(String name) { return List.of("v=spf1 mx -all"); }
        @Override public List<String> cnameTargets(String name) { return List.of(); }
        @Override public List<String> addresses(String name) { return List.of(); }
    }

    @Test void dnsInstructionsUseTheSelectorReportedForTheDomain() {
        when(store.find("company.local")).thenReturn(Optional.of(row("CREATED", null)));
        String key = "A".repeat(216);
        DkimKeyProbe probe = domain -> Optional.of(
                new DkimKeyProbe.SigningKey("company.local", "branch2", key));
        MailDomainService service = new MailDomainService(store, Optional.empty(),
                new MailDomainVerifier(new EmptyDns(), List.of("mail.company.local"), "corvex1"),
                Optional.of(probe), AcceptedDomains.staticOnly("company.local"),
                List.of("mail.company.local"), "corvex1",
                Duration.ofHours(1), Duration.ofMinutes(2), CLOCK);

        var dkim = service.dnsInstructions("company.local").stream()
                .filter(instruction -> "branch2._domainkey".equals(instruction.name()))
                .findFirst().orElseThrow();

        assertEquals("TXT", dkim.type());
        assertEquals("v=DKIM1; k=rsa; p=" + key, dkim.value());
    }

    @Test void domainKnownOnlyToTheRegistryIsReportedAsDynamicallyAccepted() {
        when(store.find("company.local")).thenReturn(Optional.of(row("CREATED", NOW)));
        when(store.deliveryDomains()).thenReturn(List.of("company.local"));
        when(store.checks(DOMAIN_ID)).thenReturn(List.of());
        MailDomainService service = new MailDomainService(store, Optional.of(admin),
                new MailDomainVerifier(new EmptyDns(), List.of("mail.company.local"), "corvex1"),
                Optional.empty(),
                new AcceptedDomains(java.util.Set.of(), store, Duration.ofSeconds(30),
                        Duration.ofMinutes(5), CLOCK),
                List.of("mail.company.local"), "corvex1",
                Duration.ofHours(1), Duration.ofMinutes(2), CLOCK);

        var view = service.get("company.local");

        assertTrue(view.acceptanceEnabled(),
                "домен без правки .env принимается, если он подтверждён в реестре");
        assertEquals("DYNAMIC", view.acceptanceSource());
    }

    @Test void duplicateRegistrationIsConflict() {
        when(store.create(eq("company.local"), any())).thenThrow(new MailDomainStore.AlreadyExistsException());
        var error = assertThrows(ResponseStatusException.class,
                () -> service(Optional.of(admin)).register("Company.Local", ACTOR));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test void registrationStoresNormalizedDomainAndAuditsActor() {
        when(store.create("company.local", ACTOR)).thenReturn(row("NOT_REQUESTED", null));
        when(store.checks(DOMAIN_ID)).thenReturn(List.of());
        var view = service(Optional.of(admin)).register(" Company.Local. ", ACTOR);
        assertEquals("company.local", view.domain());
        assertEquals("NOT_REQUESTED", view.jamesState());
        verify(store).recordEvent(eq(DOMAIN_ID), eq("company.local"), eq(ACTOR), eq("registered"), isNull());
    }

    @Test void creationThroughBridgeMarksDomainCreated() {
        when(store.find("company.local")).thenReturn(Optional.of(row("NOT_REQUESTED", null)),
                Optional.of(new MailDomainStore.Row(DOMAIN_ID, "company.local", true, "CREATED", NOW, NOW,
                        ACTOR, NOW.minusSeconds(600))));
        when(store.claimRequest(eq(DOMAIN_ID), anyList())).thenReturn(true);
        when(admin.create("company.local")).thenReturn(JamesDomainAdmin.CreateOutcome.PRESENT);
        when(store.checks(DOMAIN_ID)).thenReturn(List.of());

        var attempt = service(Optional.of(admin)).addToJames("company.local", ACTOR);

        assertFalse(attempt.uncertain());
        assertEquals("CREATED", attempt.view().jamesState());
        verify(store).completeRequest(DOMAIN_ID, "CREATED", true);
    }

    @Test void uncertainOutcomeNeverRepeatsBlindlyWhenPresenceUnknown() {
        Instant requested = NOW.minusSeconds(300);
        when(store.find("company.local")).thenReturn(Optional.of(row("REQUESTING", requested)));
        when(store.checks(DOMAIN_ID)).thenReturn(List.of());
        when(admin.presence("company.local")).thenReturn(JamesDomainAdmin.Presence.UNKNOWN);

        var attempt = service(Optional.of(admin)).addToJames("company.local", ACTOR);

        assertTrue(attempt.uncertain());
        verify(admin, never()).create(any());
        verify(store, never()).claimRequest(any(), anyList());
    }

    @Test void absentDomainAfterUncertaintyIsSafeToCreate() {
        Instant requested = NOW.minusSeconds(300);
        when(store.find("company.local")).thenReturn(
                Optional.of(row("REQUESTING", requested)),
                Optional.of(new MailDomainStore.Row(DOMAIN_ID, "company.local", true, "CREATED", NOW, NOW,
                        ACTOR, NOW.minusSeconds(600))));
        when(store.checks(DOMAIN_ID)).thenReturn(List.of());
        when(admin.presence("company.local")).thenReturn(JamesDomainAdmin.Presence.ABSENT);
        when(store.claimRequest(eq(DOMAIN_ID), anyList())).thenReturn(true);
        when(admin.create("company.local")).thenReturn(JamesDomainAdmin.CreateOutcome.PRESENT);

        var attempt = service(Optional.of(admin)).addToJames("company.local", ACTOR);

        assertFalse(attempt.uncertain());
        verify(admin).create("company.local");
    }

    @Test void createdDomainConfirmedByReadIsGuaranteedWithoutPut() {
        when(store.find("company.local")).thenReturn(Optional.of(row("CREATED", NOW)));
        when(store.checks(DOMAIN_ID)).thenReturn(List.of());
        when(admin.presence("company.local")).thenReturn(JamesDomainAdmin.Presence.PRESENT);

        var attempt = service(Optional.of(admin)).addToJames("company.local", ACTOR);

        assertFalse(attempt.uncertain());
        verify(admin, never()).create(any());
    }

    @Test void missingBridgeIsExplicitServiceUnavailable() {
        when(store.find("company.local")).thenReturn(Optional.of(row("NOT_REQUESTED", null)));
        var error = assertThrows(ResponseStatusException.class,
                () -> service(Optional.empty()).addToJames("company.local", ACTOR));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }

    @Test void verifyPersistsChecksAndReportsReadyForReceiving() {
        when(store.find("company.local")).thenReturn(Optional.of(row("CREATED", NOW)));
        var saved = new ArrayList<MailDomainVerifier.Check>();
        doAnswer(invocation -> {
            saved.addAll(invocation.getArgument(1));
            return null;
        }).when(store).saveChecks(eq(DOMAIN_ID), anyList());
        when(store.checks(DOMAIN_ID)).thenAnswer(invocation -> List.copyOf(saved));

        var view = service(Optional.of(admin)).verify("company.local", ACTOR);

        assertEquals(5, saved.size());
        assertTrue(view.receivingReady());
        assertFalse(view.sendingReady(), "без подтверждённого DKIM отправка не готова");
        assertTrue(view.dnsChecked());
        verify(store).recordEvent(eq(DOMAIN_ID), eq("company.local"), eq(ACTOR), eq("dns-verified"), anyString());
    }

    @Test void deleteOnlyTouchesRegistry() {
        when(store.find("company.local")).thenReturn(Optional.of(row("CREATED", NOW)));
        when(store.delete("company.local")).thenReturn(true);

        service(Optional.of(admin)).delete("company.local", ACTOR);

        verify(store).recordEvent(eq(DOMAIN_ID), eq("company.local"), eq(ACTOR), eq("deleted"), anyString());
        verify(store).delete("company.local");
        verify(admin, never()).create(any());
    }

    @Test void invalidDomainIsBadRequest() {
        var error = assertThrows(ResponseStatusException.class,
                () -> service(Optional.of(admin)).get("company.local/../admin"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
