package ru.corvex.mailstorage.maildomain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.Check;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.CheckName;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.CheckState;
import static org.junit.jupiter.api.Assertions.*;

class MailDomainAssessmentTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final Instant FRESH = NOW.minusSeconds(60);
    private static final Duration MAX_AGE = Duration.ofHours(1);

    private static Check check(CheckName name, CheckState state, Instant at) {
        return new Check(name, state, name + " " + state, at);
    }

    private static List<Check> healthy(Instant at) {
        return List.of(check(CheckName.MX, CheckState.PASS, at), check(CheckName.SPF, CheckState.PASS, at),
                check(CheckName.DKIM, CheckState.PASS, at), check(CheckName.DMARC, CheckState.WARN, at),
                check(CheckName.AUTODISCOVER, CheckState.WARN, at));
    }

    @Test void everythingConfirmedMeansReadyForReceivingAndSending() {
        var assessment = MailDomainAssessment.assess(true, true, healthy(FRESH), FRESH, MAX_AGE, NOW);
        assertTrue(assessment.receivingReady());
        assertTrue(assessment.sendingReady());
        assertNull(assessment.blockedReason());
        assertTrue(assessment.fresh());
    }

    @Test void unconfirmedJamesBlocksEverything() {
        var assessment = MailDomainAssessment.assess(false, true, healthy(FRESH), FRESH, MAX_AGE, NOW);
        assertFalse(assessment.receivingReady());
        assertFalse(assessment.sendingReady());
        assertTrue(assessment.blockedReason().contains("James"));
    }

    @Test void domainOutsideAcceptanceListBlocksEverything() {
        var assessment = MailDomainAssessment.assess(true, false, healthy(FRESH), FRESH, MAX_AGE, NOW);
        assertFalse(assessment.receivingReady());
        assertTrue(assessment.blockedReason().contains("CORVEX_MAIL_DOMAINS"));
    }

    @Test void staleChecksNeverCountAsReady() {
        Instant old = NOW.minus(Duration.ofHours(2));
        var assessment = MailDomainAssessment.assess(true, true, healthy(old), old, MAX_AGE, NOW);
        assertFalse(assessment.receivingReady());
        assertFalse(assessment.fresh());
        assertTrue(assessment.blockedReason().contains("устарели"));
    }

    @Test void noChecksAtAllIsNotReady() {
        var assessment = MailDomainAssessment.assess(true, true, List.of(), null, MAX_AGE, NOW);
        assertFalse(assessment.receivingReady());
        assertTrue(assessment.blockedReason().contains("не проверялся"));
    }

    @Test void mxFailureBlocksReceivingAndSending() {
        var checks = List.of(check(CheckName.MX, CheckState.FAIL, FRESH),
                check(CheckName.SPF, CheckState.PASS, FRESH), check(CheckName.DKIM, CheckState.PASS, FRESH));
        var assessment = MailDomainAssessment.assess(true, true, checks, FRESH, MAX_AGE, NOW);
        assertFalse(assessment.receivingReady());
        assertFalse(assessment.sendingReady());
        assertTrue(assessment.blockedReason().startsWith("MX"));
    }

    @Test void dkimWarningStillAllowsReceivingButNotSending() {
        var checks = List.of(check(CheckName.MX, CheckState.PASS, FRESH),
                check(CheckName.SPF, CheckState.PASS, FRESH), check(CheckName.DKIM, CheckState.WARN, FRESH));
        var assessment = MailDomainAssessment.assess(true, true, checks, FRESH, MAX_AGE, NOW);
        assertTrue(assessment.receivingReady());
        assertFalse(assessment.sendingReady());
        assertTrue(assessment.blockedReason().startsWith("DKIM"));
    }

    @Test void unknownMxIsNotTreatedAsPass() {
        var checks = List.of(check(CheckName.MX, CheckState.UNKNOWN, FRESH),
                check(CheckName.SPF, CheckState.PASS, FRESH), check(CheckName.DKIM, CheckState.PASS, FRESH));
        assertFalse(MailDomainAssessment.assess(true, true, checks, FRESH, MAX_AGE, NOW).receivingReady());
    }
}
