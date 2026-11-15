package ru.corvex.mailstorage.maildomain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.james.JamesDomainAdmin;

/**
 * Подключение собственного домена: реестр, запись в James и проверка DNS.
 *
 * <p>Готовность никогда не выводится из одного факта «домен добавлен». Она
 * складывается из подтверждённого James, фактических MX/SPF (и DKIM для
 * отправки) и свежести проверки. Любая неопределённость остаётся видимой
 * администратору, а не превращается в оптимистичный статус.
 */
public class MailDomainService {

    private static final Logger log = LoggerFactory.getLogger(MailDomainService.class);

    /** Состояния, из которых допустим новый PUT: домен заведомо отсутствует. */
    private static final List<String> RETRYABLE = List.of("NOT_REQUESTED", "FAILED", "UNCERTAIN");

    private final MailDomainStore store;
    private final Optional<JamesDomainAdmin> jamesAdmin;
    private final MailDomainVerifier verifier;
    private final Optional<DkimKeyProbe> dkimKeyProbe;
    private final AcceptedDomains accepted;
    private final List<String> expectedMxHosts;
    private final String dkimSelector;
    private final Duration maxCheckAge;
    private final Duration requestTimeout;
    private final Clock clock;

    public MailDomainService(MailDomainStore store, Optional<JamesDomainAdmin> jamesAdmin,
                             MailDomainVerifier verifier, Optional<DkimKeyProbe> dkimKeyProbe,
                             AcceptedDomains accepted, List<String> expectedMxHosts, String dkimSelector,
                             Duration maxCheckAge, Duration requestTimeout, Clock clock) {
        this.store = store;
        this.jamesAdmin = jamesAdmin;
        this.verifier = verifier;
        this.dkimKeyProbe = dkimKeyProbe;
        this.accepted = accepted;
        this.expectedMxHosts = expectedMxHosts.stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .toList();
        this.dkimSelector = dkimSelector;
        this.maxCheckAge = maxCheckAge;
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    public record View(UUID id, String domain, boolean enabled, String jamesState, Instant jamesConfirmedAt,
                       boolean jamesBridgeAvailable, boolean acceptanceEnabled, String acceptanceSource,
                       boolean dnsChecked,
                       Instant checkedAt, boolean fresh, boolean receivingReady, boolean sendingReady,
                       String blockedReason, List<MailDomainVerifier.Check> checks, List<String> warnings) { }

    public record JamesAttempt(View view, boolean uncertain) { }

    public record DnsInstruction(String type, String name, String value, Integer priority, String note) { }

    public View register(String rawDomain, UUID actor) {
        String domain = normalize(rawDomain);
        MailDomainStore.Row row;
        try {
            row = store.create(domain, actor);
        } catch (MailDomainStore.AlreadyExistsException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Домен уже добавлен");
        }
        store.recordEvent(row.id(), row.domain(), actor, "registered", null);
        return view(row);
    }

    public View get(String rawDomain) {
        return view(require(rawDomain));
    }

    public List<View> list() {
        return store.list().stream().map(this::view).toList();
    }

    /**
     * Добавляет домен в James через mTLS-мост. Повторный вызов идемпотентен,
     * пока домен подтверждён; неизвестный исход разрешается только чтением.
     */
    public JamesAttempt addToJames(String rawDomain, UUID actor) {
        MailDomainStore.Row row = require(rawDomain);
        if (!row.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Домен отключён");
        }
        JamesDomainAdmin admin = jamesAdmin.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Мост James WebAdmin не настроен (corvex.james.provision.enabled)"));
        String state = effectiveState(row);
        if ("CREATED".equals(state)) {
            JamesDomainAdmin.Presence presence = admin.presence(row.domain());
            if (presence == JamesDomainAdmin.Presence.PRESENT) {
                return new JamesAttempt(view(row), false);
            }
            if (presence == JamesDomainAdmin.Presence.UNKNOWN) {
                // Не понижаем подтверждённое состояние из-за временной ошибки чтения.
                return new JamesAttempt(view(row), true);
            }
            store.markUncertain(row.id());
            store.recordEvent(row.id(), row.domain(), actor, "james-absent", "GET /domains вернул 404");
            row = require(row.domain());
            state = "UNCERTAIN";
        }
        if ("REQUESTING".equals(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Запрос в James уже выполняется");
        }
        if ("UNCERTAIN".equals(state)) {
            JamesDomainAdmin.Presence presence = admin.presence(row.domain());
            if (presence == JamesDomainAdmin.Presence.PRESENT) {
                store.markPresent(row.id());
                store.recordEvent(row.id(), row.domain(), actor, "james-confirmed", null);
                return new JamesAttempt(view(require(row.domain())), false);
            }
            if (presence == JamesDomainAdmin.Presence.UNKNOWN) {
                return new JamesAttempt(view(row), true);
            }
            // GET 404 — домен отсутствует, поэтому повторный PUT не создаст дубль.
        }
        if (!store.claimRequest(row.id(), RETRYABLE)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Состояние домена изменилось, обновите страницу");
        }
        JamesDomainAdmin.CreateOutcome outcome;
        try {
            outcome = admin.create(row.domain());
        } catch (RuntimeException e) {
            log.warn("James domain creation outcome is uncertain for {}", row.domain());
            outcome = JamesDomainAdmin.CreateOutcome.UNCERTAIN;
        }
        if (outcome == JamesDomainAdmin.CreateOutcome.PRESENT) {
            store.completeRequest(row.id(), "CREATED", true);
            store.recordEvent(row.id(), row.domain(), actor, "james-created", null);
        } else if (outcome == JamesDomainAdmin.CreateOutcome.REJECTED) {
            store.completeRequest(row.id(), "FAILED", false);
            store.recordEvent(row.id(), row.domain(), actor, "james-rejected", null);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "James отклонил домен: проверьте имя домена и конфигурацию James");
        } else {
            store.completeRequest(row.id(), "UNCERTAIN", false);
            store.recordEvent(row.id(), row.domain(), actor, "james-uncertain", null);
        }
        MailDomainStore.Row updated = require(row.domain());
        return new JamesAttempt(view(updated), "UNCERTAIN".equals(updated.jamesState()));
    }

    /** Единственный путь получить свежие доказательства: реальный DNS-запрос. */
    public View verify(String rawDomain, UUID actor) {
        MailDomainStore.Row row = require(rawDomain);
        Optional<DkimKeyProbe.SigningKey> key =
                dkimKeyProbe.flatMap(probe -> probe.signingKey(row.domain()));
        List<MailDomainVerifier.Check> checks = verifier.verify(row.domain(), key, clock.instant());
        store.saveChecks(row.id(), checks);
        store.recordEvent(row.id(), row.domain(), actor, "dns-verified", summary(checks));
        return view(require(rawDomain));
    }

    public void delete(String rawDomain, UUID actor) {
        MailDomainStore.Row row = require(rawDomain);
        store.recordEvent(row.id(), row.domain(), actor, "deleted",
                "Удалён только реестр Corvex; домен и ящики в James не изменялись");
        if (!store.delete(row.domain())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Домен уже удалён");
        }
    }

    /** Точные записи, которые администратор публикует у регистратора. */
    public List<DnsInstruction> dnsInstructions(String rawDomain) {
        MailDomainStore.Row row = require(rawDomain);
        Optional<DkimKeyProbe.SigningKey> key =
                dkimKeyProbe.flatMap(probe -> probe.signingKey(row.domain()));
        List<DnsInstruction> instructions = new ArrayList<>();
        if (expectedMxHosts.isEmpty()) {
            instructions.add(new DnsInstruction("MX", "@", null, 10,
                    "Не задан CORVEX_DOMAIN: укажите внешнее имя сервера, иначе MX не сформировать"));
        } else {
            for (String host : expectedMxHosts) {
                instructions.add(new DnsInstruction("MX", "@", host, 10,
                        "Единственный адрес приёма почты домена"));
            }
        }
        instructions.add(new DnsInstruction("TXT", "@", "v=spf1 mx -all", null,
                "Если SPF уже существует, добавьте механизм mx в него: две SPF-записи недопустимы"));
        String dkimName = dkimSelector + "._domainkey";
        Optional<DkimKeyProbe.SigningKey> domainKey =
                key.filter(signing -> signing.domain().equalsIgnoreCase(row.domain()));
        if (domainKey.isPresent()) {
            instructions.add(new DnsInstruction("TXT", dkimName,
                    "v=DKIM1; k=rsa; p=" + domainKey.get().publicKeyBase64(), null,
                    "Публичный ключ действующего отправителя Corvex"));
        } else {
            instructions.add(new DnsInstruction("TXT", dkimName, null, null,
                    "Публичный ключ недоступен или сервер подписывает другой домен: "
                            + "проверьте DKIM в mail-smtp-service"));
        }
        instructions.add(new DnsInstruction("TXT", "_dmarc", "v=DMARC1; p=quarantine", null,
                "Рекомендуемая политика; не влияет на приём почты"));
        if (!expectedMxHosts.isEmpty()) {
            instructions.add(new DnsInstruction("CNAME", "autodiscover", expectedMxHosts.get(0), null,
                    "Необязательно: автоконфигурация почтовых клиентов"));
        }
        return List.copyOf(instructions);
    }

    private MailDomainStore.Row require(String rawDomain) {
        String domain = normalize(rawDomain);
        return store.find(domain).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Домен не найден"));
    }

    private static String normalize(String rawDomain) {
        try {
            return MailDomainName.normalize(rawDomain);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Запрос, переживший перезапуск, не может остаться «в процессе»: исход
     * неизвестен, и до чтения из James домен не считается созданным.
     */
    private String effectiveState(MailDomainStore.Row row) {
        if ("REQUESTING".equals(row.jamesState())) {
            Instant requestedAt = row.jamesRequestedAt();
            if (requestedAt == null || requestedAt.plus(requestTimeout).isBefore(clock.instant())) {
                return "UNCERTAIN";
            }
        }
        return row.jamesState();
    }

    private View view(MailDomainStore.Row row) {
        List<MailDomainVerifier.Check> checks = ordered(store.checks(row.id()));
        Instant checkedAt = checks.stream().map(MailDomainVerifier.Check::observedAt)
                .max(Instant::compareTo).orElse(null);
        String state = effectiveState(row);
        // Приём возможен, если домен либо в статическом списке, либо реально
        // подтверждён в James и включён в реестре. Источник показываем отдельно:
        // «нет в списке» и «список недоступен» требуют разных действий.
        AcceptedDomains.Source acceptance =
                row.enabled() ? accepted.source(row.domain()) : AcceptedDomains.Source.ABSENT;
        boolean acceptanceEnabled = acceptance == AcceptedDomains.Source.STATIC
                || acceptance == AcceptedDomains.Source.DYNAMIC;
        MailDomainAssessment assessment = MailDomainAssessment.assess("CREATED".equals(state),
                acceptanceEnabled, checks, checkedAt, maxCheckAge, clock.instant());
        List<String> warnings = new ArrayList<>();
        if (jamesAdmin.isEmpty()) {
            warnings.add("Мост James WebAdmin не настроен: домен нельзя добавить в James из Corvex");
        }
        if ("UNCERTAIN".equals(state)) {
            warnings.add("Исход добавления в James неизвестен. Повторная запись выполняется только "
                    + "после достоверного ответа GET /domains");
        }
        if (!row.enabled()) {
            warnings.add("Домен отключён в реестре: приём и отправка писем не выполняются");
        } else if (acceptance == AcceptedDomains.Source.UNAVAILABLE) {
            warnings.add("Список принимаемых доменов недоступен: сервер не может подтвердить, "
                    + "что домен уже принимается");
        } else if (!acceptanceEnabled) {
            warnings.add("Домен не принимается: его нет ни в CORVEX_MAIL_DOMAINS, ни среди "
                    + "подтверждённых в James доменов реестра");
        }
        if (checks.isEmpty()) {
            warnings.add("DNS ещё не проверялся");
        } else if (!assessment.fresh()) {
            warnings.add("Результаты DNS-проверки устарели: выполните проверку повторно");
        }
        return new View(row.id(), row.domain(), row.enabled(), state, row.jamesConfirmedAt(),
                jamesAdmin.isPresent(), acceptanceEnabled, acceptance.name(), !checks.isEmpty(), checkedAt,
                assessment.fresh(), assessment.receivingReady(), assessment.sendingReady(),
                assessment.blockedReason(), checks, List.copyOf(new LinkedHashSet<>(warnings)));
    }

    private static List<MailDomainVerifier.Check> ordered(List<MailDomainVerifier.Check> checks) {
        List<MailDomainVerifier.Check> ordered = new ArrayList<>();
        for (MailDomainVerifier.CheckName name : MailDomainVerifier.CheckName.values()) {
            checks.stream().filter(check -> check.name() == name).findFirst().ifPresent(ordered::add);
        }
        return List.copyOf(ordered);
    }

    private static String summary(List<MailDomainVerifier.Check> checks) {
        return MailDomainVerifier.truncate(checks.stream()
                .map(check -> check.name() + "=" + check.state())
                .collect(java.util.stream.Collectors.joining(" ")));
    }
}
