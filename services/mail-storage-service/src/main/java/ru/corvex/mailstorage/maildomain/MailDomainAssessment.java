package ru.corvex.mailstorage.maildomain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Итоговая оценка готовности домена. Вычисляется из сохранённых проверок и
 * поэтому всегда содержит {@code fresh}: старые или отсутствующие проверки не
 * дают права называть домен готовым.
 *
 * <p>Приём почты требует одновременно: домен создан в James, домен включён в
 * список принимаемых доменов Corvex, свежие проверки MX и SPF в состоянии PASS.
 * Отправка дополнительно требует PASS по DKIM (ключ сервера).
 */
public record MailDomainAssessment(boolean receivingReady, boolean sendingReady, String blockedReason,
                                   boolean fresh) {

    public static MailDomainAssessment assess(boolean jamesCreated, boolean acceptanceEnabled,
                                              List<MailDomainVerifier.Check> checks, Instant checkedAt,
                                              Duration maxAge, Instant now) {
        boolean fresh = checkedAt != null && !checkedAt.plus(maxAge).isBefore(now);
        if (!jamesCreated) {
            return new MailDomainAssessment(false, false, "Домен не подтверждён в James", false);
        }
        if (!acceptanceEnabled) {
            return new MailDomainAssessment(false, false,
                    "Домен не входит в список принимаемых доменов Corvex (CORVEX_MAIL_DOMAINS)", fresh);
        }
        if (!fresh) {
            String reason = checkedAt == null ? "DNS ещё не проверялся" : "Результаты DNS-проверки устарели";
            return new MailDomainAssessment(false, false, reason, false);
        }
        MailDomainVerifier.CheckState mx = state(checks, MailDomainVerifier.CheckName.MX);
        MailDomainVerifier.CheckState spf = state(checks, MailDomainVerifier.CheckName.SPF);
        MailDomainVerifier.CheckState dkim = state(checks, MailDomainVerifier.CheckName.DKIM);
        String blocked = null;
        if (mx != MailDomainVerifier.CheckState.PASS) {
            blocked = "MX: " + describe(mx, checks, MailDomainVerifier.CheckName.MX);
        } else if (spf != MailDomainVerifier.CheckState.PASS) {
            blocked = "SPF: " + describe(spf, checks, MailDomainVerifier.CheckName.SPF);
        }
        boolean receiving = blocked == null;
        boolean sending = receiving && dkim == MailDomainVerifier.CheckState.PASS;
        if (sending) {
            return new MailDomainAssessment(true, true, null, true);
        }
        if (receiving) {
            blocked = "DKIM: " + describe(dkim, checks, MailDomainVerifier.CheckName.DKIM);
        }
        return new MailDomainAssessment(receiving, false, blocked, true);
    }

    private static MailDomainVerifier.CheckState state(List<MailDomainVerifier.Check> checks,
                                                       MailDomainVerifier.CheckName name) {
        return find(checks, name).map(MailDomainVerifier.Check::state)
                .orElse(MailDomainVerifier.CheckState.UNKNOWN);
    }

    private static String describe(MailDomainVerifier.CheckState state, List<MailDomainVerifier.Check> checks,
                                   MailDomainVerifier.CheckName name) {
        if (state == MailDomainVerifier.CheckState.PASS) {
            return "проверка пройдена";
        }
        return find(checks, name).map(MailDomainVerifier.Check::detail).orElse("проверка не выполнялась");
    }

    private static Optional<MailDomainVerifier.Check> find(List<MailDomainVerifier.Check> checks,
                                                           MailDomainVerifier.CheckName name) {
        return checks.stream().filter(check -> check.name() == name).findFirst();
    }
}
