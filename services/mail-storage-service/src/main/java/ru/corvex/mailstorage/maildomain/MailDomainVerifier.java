package ru.corvex.mailstorage.maildomain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Проверка домена по фактическим DNS-ответам.
 *
 * <p>Ни одна проверка не «оптимистична»: ошибка резолвера даёт UNKNOWN, а не
 * PASS/FAIL, и нехватка конфигурации (например, неизвестный MX-узел сервера)
 * тоже даёт UNKNOWN. DKIM сравнивается с ключом, которым сервер реально
 * подписывает письма; если ключ получить нельзя, проверка остаётся WARN и
 * отправка не считается готовой.
 */
public class MailDomainVerifier {

    public enum CheckName { MX, SPF, DKIM, DMARC, AUTODISCOVER }

    public enum CheckState { PASS, WARN, FAIL, UNKNOWN }

    public record Check(CheckName name, CheckState state, String detail, Instant observedAt) { }

    private static final int MAX_DETAIL = 300;

    private final DnsRecordSource dns;
    private final List<String> expectedMxHosts;
    private final String dkimSelector;

    public MailDomainVerifier(DnsRecordSource dns, List<String> expectedMxHosts, String dkimSelector) {
        this.dns = dns;
        this.expectedMxHosts = expectedMxHosts.stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .map(host -> host.endsWith(".") ? host.substring(0, host.length() - 1) : host)
                .filter(host -> !host.isEmpty())
                .toList();
        this.dkimSelector = dkimSelector;
    }

    public List<Check> verify(String domain, Optional<DkimKeyProbe.SigningKey> signingKey, Instant now) {
        List<Check> checks = new ArrayList<>(5);
        checks.add(mx(domain, now));
        checks.add(spf(domain, now));
        checks.add(dkim(domain, signingKey, now));
        checks.add(dmarc(domain, now));
        checks.add(autodiscover(domain, now));
        return List.copyOf(checks);
    }

    private Check mx(String domain, Instant now) {
        List<String> hosts;
        try {
            hosts = dns.mxHosts(domain);
        } catch (DnsRecordSource.DnsLookupException e) {
            return unknown(CheckName.MX, "Не удалось получить MX: " + e.getMessage(), now);
        }
        if (hosts.isEmpty()) {
            return new Check(CheckName.MX, CheckState.FAIL, "MX-запись не найдена", now);
        }
        if (expectedMxHosts.isEmpty()) {
            return unknown(CheckName.MX, "Не задан ожидаемый MX-узел сервера (CORVEX_DOMAIN)", now);
        }
        boolean matches = hosts.stream().anyMatch(expectedMxHosts::contains);
        String found = truncate(String.join(", ", hosts));
        if (matches) {
            return new Check(CheckName.MX, CheckState.PASS, "MX: " + found, now);
        }
        return new Check(CheckName.MX, CheckState.FAIL,
                truncate("MX указывает на " + found + ", ожидается " + String.join(", ", expectedMxHosts)), now);
    }

    private Check spf(String domain, Instant now) {
        List<String> records;
        try {
            records = dns.txt(domain);
        } catch (DnsRecordSource.DnsLookupException e) {
            return unknown(CheckName.SPF, "Не удалось получить TXT: " + e.getMessage(), now);
        }
        List<String> spf = records.stream()
                .map(String::trim)
                .filter(record -> record.toLowerCase(Locale.ROOT).startsWith("v=spf1"))
                .toList();
        if (spf.isEmpty()) {
            return new Check(CheckName.SPF, CheckState.FAIL, "SPF-запись не найдена", now);
        }
        if (spf.size() > 1) {
            return new Check(CheckName.SPF, CheckState.FAIL,
                    "Найдено несколько SPF-записей (" + spf.size() + "): RFC 7208 требует одну", now);
        }
        String record = spf.get(0);
        boolean authorizes = Arrays.stream(record.split("\\s+"))
                .skip(1)
                .map(token -> token.toLowerCase(Locale.ROOT))
                .anyMatch(MailDomainVerifier::authorizingMechanism);
        if (authorizes) {
            return new Check(CheckName.SPF, CheckState.PASS, truncate("SPF: " + record), now);
        }
        return new Check(CheckName.SPF, CheckState.FAIL,
                truncate("SPF-запись не разрешает отправку с сервера Corvex: " + record), now);
    }

    private static boolean authorizingMechanism(String token) {
        return token.equals("mx") || token.startsWith("mx:") || token.startsWith("mx/")
                || token.equals("a") || token.startsWith("a:") || token.startsWith("a/")
                || token.startsWith("include:") || token.startsWith("ip4:") || token.startsWith("ip6:")
                || token.startsWith("ptr") || token.startsWith("exists:") || token.startsWith("redirect=");
    }

    private Check dkim(String domain, Optional<DkimKeyProbe.SigningKey> signingKey, Instant now) {
        // Селектор может различаться по доменам (`<domain>.selector` в mail-smtp).
        // Проверять запись чужого селектора бессмысленно: имя DNS-записи берём у
        // сервера, если он подтвердил ключ именно этого домена.
        String selector = signingKey
                .filter(key -> key.domain().equalsIgnoreCase(domain))
                .map(DkimKeyProbe.SigningKey::selector)
                .filter(value -> value != null && !value.isBlank())
                .orElse(dkimSelector);
        String name = selector + "._domainkey." + domain;
        List<String> records;
        try {
            records = dns.txt(name);
        } catch (DnsRecordSource.DnsLookupException e) {
            return unknown(CheckName.DKIM, "Не удалось получить " + name + ": " + e.getMessage(), now);
        }
        if (records.isEmpty()) {
            return new Check(CheckName.DKIM, CheckState.FAIL, "DKIM-запись " + name + " не найдена", now);
        }
        if (records.size() > 1) {
            return new Check(CheckName.DKIM, CheckState.FAIL,
                    "Найдено несколько DKIM-записей " + name + ": ожидается одна", now);
        }
        String record = records.get(0);
        String publishedKey = tag(record, "p");
        if (publishedKey.isEmpty()) {
            return new Check(CheckName.DKIM, CheckState.FAIL,
                    "DKIM-ключ " + name + " отозван или пуст (p=)", now);
        }
        String algorithm = tag(record, "k");
        int minimum = algorithm.equalsIgnoreCase("ed25519") ? 40 : 216;
        if (publishedKey.length() < minimum) {
            return new Check(CheckName.DKIM, CheckState.FAIL,
                    "DKIM-ключ " + name + " короче допустимого размера", now);
        }
        if (signingKey.isPresent()) {
            DkimKeyProbe.SigningKey key = signingKey.get();
            if (!key.domain().equalsIgnoreCase(domain)) {
                return new Check(CheckName.DKIM, CheckState.FAIL,
                        "Сервер подписывает домен " + key.domain() + ", а не " + domain, now);
            }
            if (!publishedKey.equals(key.publicKeyBase64())) {
                return new Check(CheckName.DKIM, CheckState.FAIL,
                        "Опубликован другой DKIM-ключ, чем ключ сервера (" + name + ")", now);
            }
            return new Check(CheckName.DKIM, CheckState.PASS,
                    "DKIM-ключ совпадает с ключом сервера (" + name + ")", now);
        }
        return new Check(CheckName.DKIM, CheckState.WARN,
                "Ключ " + name + " опубликован, но сервер не подтвердил соответствие", now);
    }

    private Check dmarc(String domain, Instant now) {
        List<String> records;
        try {
            records = dns.txt("_dmarc." + domain);
        } catch (DnsRecordSource.DnsLookupException e) {
            return unknown(CheckName.DMARC, "Не удалось получить _dmarc." + domain, now);
        }
        boolean present = records.stream()
                .map(String::trim)
                .anyMatch(record -> record.toLowerCase(Locale.ROOT).startsWith("v=dmarc1"));
        if (present) {
            return new Check(CheckName.DMARC, CheckState.PASS, "DMARC-запись найдена", now);
        }
        return new Check(CheckName.DMARC, CheckState.WARN,
                "DMARC не настроен; это не блокирует приём, но снижает доверие к домену", now);
    }

    private Check autodiscover(String domain, Instant now) {
        String name = "autodiscover." + domain;
        try {
            List<String> cnames = dns.cnameTargets(name);
            if (!cnames.isEmpty()) {
                return new Check(CheckName.AUTODISCOVER, CheckState.PASS,
                        truncate("CNAME " + name + " -> " + String.join(", ", cnames)), now);
            }
            List<String> addresses = dns.addresses(name);
            if (!addresses.isEmpty()) {
                return new Check(CheckName.AUTODISCOVER, CheckState.PASS, "A-запись " + name + " найдена", now);
            }
        } catch (DnsRecordSource.DnsLookupException e) {
            return unknown(CheckName.AUTODISCOVER, "Не удалось получить " + name, now);
        }
        return new Check(CheckName.AUTODISCOVER, CheckState.WARN,
                "autodiscover не настроен: клиенты придётся настраивать вручную", now);
    }

    private static String tag(String record, String name) {
        for (String part : record.split(";")) {
            int equals = part.indexOf('=');
            if (equals < 0) {
                continue;
            }
            if (part.substring(0, equals).trim().equalsIgnoreCase(name)) {
                return part.substring(equals + 1).replaceAll("\\s+", "");
            }
        }
        return "";
    }

    private static Check unknown(CheckName name, String detail, Instant now) {
        return new Check(name, CheckState.UNKNOWN, truncate(detail), now);
    }

    static String truncate(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
        return clean.length() <= MAX_DETAIL ? clean : clean.substring(0, MAX_DETAIL) + "…";
    }
}
