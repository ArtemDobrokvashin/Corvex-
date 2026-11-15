package ru.corvex.mailstorage.maildomain;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.Check;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.CheckName;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.CheckState;
import static org.junit.jupiter.api.Assertions.*;

/** Только фальшивый DNS-источник: сеть не используется. */
class MailDomainVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final String DOMAIN = "company.local";

    private static final class FakeDns implements DnsRecordSource {
        final Map<String, List<String>> mx = new HashMap<>();
        final Map<String, List<String>> txt = new HashMap<>();
        final Map<String, List<String>> cname = new HashMap<>();
        final Map<String, List<String>> a = new HashMap<>();
        String failFor;

        @Override public List<String> mxHosts(String domain) {
            fail(domain);
            return mx.getOrDefault(domain, List.of());
        }

        @Override public List<String> txt(String name) {
            fail(name);
            return txt.getOrDefault(name, List.of());
        }

        @Override public List<String> cnameTargets(String name) {
            fail(name);
            return cname.getOrDefault(name, List.of());
        }

        @Override public List<String> addresses(String name) {
            fail(name);
            return a.getOrDefault(name, List.of());
        }

        private void fail(String name) {
            if (failFor != null && failFor.equals(name)) {
                throw new DnsLookupException("timeout");
            }
        }
    }

    private final FakeDns dns = new FakeDns();
    private final MailDomainVerifier verifier =
            new MailDomainVerifier(dns, List.of("mail.company.local"), "corvex1");

    private Check check(List<Check> checks, CheckName name) {
        return checks.stream().filter(c -> c.name() == name).findFirst().orElseThrow();
    }

    private void healthyDns() {
        dns.mx.put(DOMAIN, List.of("mail.company.local"));
        dns.txt.put(DOMAIN, List.of("v=spf1 mx -all"));
        dns.txt.put("corvex1._domainkey." + DOMAIN, List.of("v=DKIM1; k=rsa; p=" + "A".repeat(216)));
        dns.txt.put("_dmarc." + DOMAIN, List.of("v=DMARC1; p=quarantine"));
        dns.cname.put("autodiscover." + DOMAIN, List.of("mail.company.local"));
    }

    @Test void healthyDomainPassesRequiredChecks() {
        healthyDns();
        List<Check> checks = verifier.verify(DOMAIN, Optional.empty(), NOW);
        assertEquals(CheckState.PASS, check(checks, CheckName.MX).state());
        assertEquals(CheckState.PASS, check(checks, CheckName.SPF).state());
        assertEquals(CheckState.WARN, check(checks, CheckName.DKIM).state());
        assertEquals(CheckState.PASS, check(checks, CheckName.DMARC).state());
        assertEquals(CheckState.PASS, check(checks, CheckName.AUTODISCOVER).state());
    }

    @Test void missingMxIsFailureNotAssumption() {
        healthyDns();
        dns.mx.clear();
        assertEquals(CheckState.FAIL, check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.MX).state());
    }

    @Test void mxPointingElsewhereIsFailure() {
        healthyDns();
        dns.mx.put(DOMAIN, List.of("mail.other.example"));
        Check mx = check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.MX);
        assertEquals(CheckState.FAIL, mx.state());
        assertTrue(mx.detail().contains("mail.other.example"));
    }

    @Test void resolverErrorIsUnknownNotFailure() {
        healthyDns();
        dns.failFor = DOMAIN;
        assertEquals(CheckState.UNKNOWN, check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.MX).state());
        assertEquals(CheckState.UNKNOWN, check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.SPF).state());
    }

    @Test void spfWithoutAuthorizingMechanismFails() {
        healthyDns();
        dns.txt.put(DOMAIN, List.of("v=spf1 -all"));
        assertEquals(CheckState.FAIL, check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.SPF).state());
    }

    @Test void twoSpfRecordsFail() {
        healthyDns();
        dns.txt.put(DOMAIN, List.of("v=spf1 mx -all", "v=spf1 include:other.example -all"));
        assertEquals(CheckState.FAIL, check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.SPF).state());
    }

    @Test void dkimMatchesServerKey() {
        healthyDns();
        String key = "A".repeat(216);
        var signing = new DkimKeyProbe.SigningKey(DOMAIN, "corvex1", key);
        assertEquals(CheckState.PASS, check(verifier.verify(DOMAIN, Optional.of(signing), NOW), CheckName.DKIM).state());
    }

    @Test void dkimWithAnotherKeyFails() {
        healthyDns();
        var signing = new DkimKeyProbe.SigningKey(DOMAIN, "corvex1", "B".repeat(216));
        assertEquals(CheckState.FAIL, check(verifier.verify(DOMAIN, Optional.of(signing), NOW), CheckName.DKIM).state());
    }

    @Test void dkimForAnotherDomainFails() {
        healthyDns();
        var signing = new DkimKeyProbe.SigningKey("other.example", "corvex1", "A".repeat(216));
        Check dkim = check(verifier.verify(DOMAIN, Optional.of(signing), NOW), CheckName.DKIM);
        assertEquals(CheckState.FAIL, dkim.state());
        assertTrue(dkim.detail().contains("other.example"));
    }

    @Test void revokedDkimKeyFails() {
        healthyDns();
        dns.txt.put("corvex1._domainkey." + DOMAIN, List.of("v=DKIM1; k=rsa; p="));
        assertEquals(CheckState.FAIL, check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.DKIM).state());
    }

    @Test void missingDmarcIsOnlyWarning() {
        healthyDns();
        dns.txt.remove("_dmarc." + DOMAIN);
        assertEquals(CheckState.WARN, check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.DMARC).state());
    }

    @Test void detailIsBoundedAndHasNoControlCharacters() {
        healthyDns();
        dns.txt.put(DOMAIN, List.of("v=spf1 mx -all " + "x".repeat(1000) + "\u0007"));
        Check spf = check(verifier.verify(DOMAIN, Optional.empty(), NOW), CheckName.SPF);
        assertTrue(spf.detail().length() <= 301);
        assertFalse(spf.detail().contains("\u0007"));
    }
}
