package ru.corvex.mailstorage.maildomain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Какие домены Corvex принимает как собственные.
 *
 * <p>Источников два: статический список из `CORVEX_MAIL_DOMAINS` (всегда
 * действует, даже если БД недоступна) и реестр доменов, подтверждённых в James.
 * Реестр читается с TTL и сохраняет последнее успешное значение: временная
 * ошибка БД не должна внезапно превращать собственный домен в чужой.
 *
 * <p>Третий ответ — {@link Verdict#UNKNOWN} — обязателен. Он означает «список
 * получить не удалось и сведений нет». Вызывающая сторона не имеет права
 * трактовать его как «чужой домен»: для входящей почты это временный отказ
 * (отправитель повторит), для отправки — явная ошибка, а не молчаливая попытка
 * релея на собственный домен.
 */
public final class AcceptedDomains {

    private static final Logger log = LoggerFactory.getLogger(AcceptedDomains.class);

    /** Для админского API: откуда взялось решение по конкретному домену. */
    public enum Source { STATIC, DYNAMIC, ABSENT, UNAVAILABLE }

    public enum Verdict { LOCAL, FOREIGN, UNKNOWN }

    private record State(Set<String> domains, Instant loadedAt, Instant attemptedAt) { }

    private final Set<String> configured;
    private final MailDomainStore store;
    private final Duration ttl;
    private final Duration maxStale;
    private final Clock clock;
    private volatile State state;

    public AcceptedDomains(Set<String> configured, MailDomainStore store, Duration ttl, Duration maxStale,
                           Clock clock) {
        this.configured = configured.stream()
                .map(domain -> domain.trim().toLowerCase(Locale.ROOT))
                .filter(domain -> !domain.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.store = store;
        this.ttl = ttl;
        this.maxStale = maxStale;
        this.clock = clock;
    }

    /** Только статический список: тесты и режимы без реестра. */
    public static AcceptedDomains staticOnly(String csv) {
        return new AcceptedDomains(parse(csv), null, Duration.ofMinutes(1), Duration.ofMinutes(5), Clock.systemUTC());
    }

    public Set<String> configuredDomains() {
        return configured;
    }

    public Verdict classify(String domain) {
        String value = normalize(domain);
        if (value.isEmpty()) {
            return Verdict.FOREIGN;
        }
        if (configured.contains(value)) {
            return Verdict.LOCAL;
        }
        if (store == null) {
            // Динамического источника нет вовсе (тесты, режим без реестра):
            // «неизвестно» здесь означало бы недоступность, которой не существует.
            return Verdict.FOREIGN;
        }
        State current = current();
        if (current == null) {
            return Verdict.UNKNOWN;
        }
        if (current.domains().contains(value)) {
            return Verdict.LOCAL;
        }
        // Устаревшее значение нельзя считать доказательством «домен чужой»:
        // домен мог быть добавлен уже после последнего успешного чтения.
        if (isStale(current)) {
            return Verdict.UNKNOWN;
        }
        return Verdict.FOREIGN;
    }

    /** Решение для админской карточки домена. */
    public Source source(String domain) {
        String value = normalize(domain);
        if (configured.contains(value)) {
            return Source.STATIC;
        }
        if (store == null) {
            return Source.ABSENT;
        }
        State current = current();
        if (current == null || isStale(current)) {
            return Source.UNAVAILABLE;
        }
        return current.domains().contains(value) ? Source.DYNAMIC : Source.ABSENT;
    }

    /** Последнее успешное содержимое реестра; пусто, если его ещё не читали. */
    public Set<String> dynamicDomains() {
        State current = state;
        return current == null ? Set.of() : current.domains();
    }

    public Instant loadedAt() {
        State current = state;
        return current == null ? null : current.loadedAt();
    }

    /** Гарантирует попытку чтения, если кэш ещё пуст или устарел. */
    public void ensureFresh() {
        if (store != null) {
            current();
        }
    }

    /** Принудительное обновление (тесты и диагностика); ошибки не пробрасываются. */
    public void refresh() {
        load(true);
    }

    public boolean isLocal(String email) {
        return classify(domainOf(email)) == Verdict.LOCAL;
    }

    private boolean isStale(State current) {
        return current.loadedAt() == null
                || current.loadedAt().plus(maxStale).isBefore(clock.instant());
    }

    /**
     * Чтение с TTL и single-flight. Повторная попытка после ошибки выполняется
     * не чаще TTL: иначе недоступная БД получала бы запрос на каждое письмо.
     */
    private State current() {
        if (store == null) {
            return null;
        }
        Instant now = clock.instant();
        State cached = state;
        if (cached != null && cached.loadedAt() != null && cached.loadedAt().plus(ttl).isAfter(now)) {
            return cached;
        }
        if (cached != null && cached.attemptedAt() != null && cached.attemptedAt().plus(ttl).isAfter(now)) {
            return cached;
        }
        return load(false);
    }

    private synchronized State load(boolean forced) {
        Instant now = clock.instant();
        State cached = state;
        if (!forced && cached != null) {
            boolean fresh = cached.loadedAt() != null && cached.loadedAt().plus(ttl).isAfter(now);
            boolean backingOff = cached.attemptedAt() != null && cached.attemptedAt().plus(ttl).isAfter(now);
            if (fresh || backingOff) {
                return cached;
            }
        }
        try {
            Set<String> domains = Set.copyOf(store.deliveryDomains());
            var loaded = new State(domains, now, now);
            state = loaded;
            return loaded;
        } catch (RuntimeException e) {
            log.warn("Список доменов из реестра недоступен: {}", e.getClass().getSimpleName());
            // Последнее успешное значение сохраняется: его устаревание решает classify().
            if (cached != null) {
                var attempted = new State(cached.domains(), cached.loadedAt(), now);
                state = attempted;
                return attempted;
            }
            var failed = new State(Set.of(), null, now);
            state = failed;
            return failed;
        }
    }

    private static String normalize(String domain) {
        return domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
    }

    private static String domainOf(String email) {
        int at = email == null ? -1 : email.lastIndexOf('@');
        return at < 0 ? "" : email.substring(at + 1);
    }

    private static Set<String> parse(String csv) {
        if (csv == null) {
            return Set.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toSet());
    }
}
