package ru.corvex.mailstorage.maildomain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.corvex.mailstorage.james.JamesDomainAdmin;

/**
 * Проводка реестра доменов. Мост James подключается необязательно: без него
 * реестр и DNS-проверки работают, а попытка записи в James возвращает 503 с
 * явной причиной вместо ложного успеха.
 */
@Configuration(proxyBeanMethods = false)
public class MailDomainConfiguration {

    @Bean
    MailDomainStore mailDomainStore(JdbcTemplate jdbc) {
        return new MailDomainStore(jdbc);
    }

    @Bean
    DnsRecordSource dnsRecordSource() {
        return new DnsjavaRecordSource();
    }

    /**
     * Принимаемые домены: `CORVEX_MAIL_DOMAINS` плюс реестр, подтверждённый в
     * James. TTL ограничивает частоту чтения БД, max-stale — срок, после
     * которого устаревшее значение перестаёт считаться доказательством.
     */
    @Bean
    AcceptedDomains acceptedDomains(MailDomainStore store,
            @Value("${corvex.mail.domains:${CORVEX_MAIL_DOMAINS:company.local}}") String configured,
            @Value("${corvex.domains.registry-ttl-seconds:30}") long ttlSeconds,
            @Value("${corvex.domains.registry-max-stale-seconds:300}") long maxStaleSeconds) {
        return new AcceptedDomains(new java.util.LinkedHashSet<>(csv(configured)), store,
                Duration.ofSeconds(ttlSeconds), Duration.ofSeconds(maxStaleSeconds), Clock.systemUTC());
    }

    @Bean
    MailDomainVerifier mailDomainVerifier(DnsRecordSource dns,
            @Value("${corvex.domains.expected-mx-hosts:${CORVEX_DOMAIN:mail.company.local}}") String mxHosts,
            @Value("${corvex.domains.dkim-selector:${CORVEX_DKIM_SELECTOR:corvex1}}") String selector) {
        return new MailDomainVerifier(dns, csv(mxHosts), selector);
    }

    @Bean
    DkimKeyProbe dkimKeyProbe(ObjectMapper mapper,
            @Value("${corvex.domains.dkim-key-url:}") String url,
            @Value("${corvex.security.internal-token:${CORVEX_INTERNAL_API_TOKEN:}}") String token) {
        if (url == null || url.isBlank()) {
            // Без адреса проверка DKIM честно остаётся «соответствие не подтверждено».
            return Optional::empty;
        }
        return new HttpDkimKeyProbe(URI.create(url), token, mapper);
    }

    @Bean
    MailDomainService mailDomainService(MailDomainStore store, ObjectProvider<JamesDomainAdmin> james,
            MailDomainVerifier verifier, ObjectProvider<DkimKeyProbe> probe, AcceptedDomains acceptedDomains,
            @Value("${corvex.domains.expected-mx-hosts:${CORVEX_DOMAIN:mail.company.local}}") String mxHosts,
            @Value("${corvex.domains.dkim-selector:${CORVEX_DKIM_SELECTOR:corvex1}}") String dkimSelector,
            @Value("${corvex.domains.max-check-age-seconds:3600}") long maxCheckAgeSeconds,
            @Value("${corvex.domains.james-request-timeout-seconds:120}") long requestTimeoutSeconds) {
        return new MailDomainService(store, Optional.ofNullable(james.getIfAvailable()), verifier,
                Optional.ofNullable(probe.getIfAvailable()), acceptedDomains, csv(mxHosts), dkimSelector,
                Duration.ofSeconds(maxCheckAgeSeconds), Duration.ofSeconds(requestTimeoutSeconds),
                Clock.systemUTC());
    }

    static List<String> csv(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(part -> !part.isEmpty()).toList();
    }
}
