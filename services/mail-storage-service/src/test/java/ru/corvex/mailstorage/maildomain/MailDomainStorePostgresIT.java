package ru.corvex.mailstorage.maildomain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.Check;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.CheckName;
import ru.corvex.mailstorage.maildomain.MailDomainVerifier.CheckState;
import static org.junit.jupiter.api.Assertions.*;

/** Изолированный PostgreSQL: James и почта не затрагиваются. */
@Testcontainers(disabledWithoutDocker = true)
class MailDomainStorePostgresIT {

    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:16.9"))
            .withEnv("POSTGRES_USER", "synthetic")
            .withEnv("POSTGRES_PASSWORD", "synthetic")
            .withEnv("POSTGRES_DB", "synthetic")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                    .forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    static JdbcTemplate jdbc;
    static MailDomainStore store;

    @BeforeAll
    static void schema() {
        var data = new DriverManagerDataSource("jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/synthetic", "synthetic", "synthetic");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V7__mail_domains.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
        store = new MailDomainStore(jdbc);
    }

    private static String uniqueDomain() {
        return "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".example";
    }

    @Test void uniqueLowercaseDomainIsEnforced() {
        String domain = uniqueDomain();
        var row = store.create(domain, UUID.randomUUID());
        assertEquals(domain, row.domain());
        assertTrue(store.find(domain).isPresent());
        assertThrows(MailDomainStore.AlreadyExistsException.class,
                () -> store.create(domain, UUID.randomUUID()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO mail_domains (domain, created_by) VALUES (?, ?)",
                        domain.toUpperCase(java.util.Locale.ROOT), UUID.randomUUID()));
    }

    @Test void stateTransitionsAreCompareAndSet() {
        String domain = uniqueDomain();
        UUID actor = UUID.randomUUID();
        var row = store.create(domain, actor);

        assertTrue(store.claimRequest(row.id(), List.of("NOT_REQUESTED")));
        assertFalse(store.claimRequest(row.id(), List.of("NOT_REQUESTED")), "второй запрос не должен стартовать");
        assertTrue(store.completeRequest(row.id(), "CREATED", true));
        assertFalse(store.completeRequest(row.id(), "FAILED", false), "завершение возможно только из REQUESTING");
        assertEquals("CREATED", store.find(domain).orElseThrow().jamesState());
        assertNotNull(store.find(domain).orElseThrow().jamesConfirmedAt());
    }

    @Test void checksAreUpsertedAndOrderedByName() {
        String domain = uniqueDomain();
        var row = store.create(domain, UUID.randomUUID());
        Instant now = Instant.now();
        store.saveChecks(row.id(), List.of(
                new Check(CheckName.MX, CheckState.PASS, "MX: mail.example", now),
                new Check(CheckName.SPF, CheckState.FAIL, "SPF нет", now)));
        store.saveChecks(row.id(), List.of(
                new Check(CheckName.SPF, CheckState.PASS, "SPF: v=spf1 mx -all", now.plusSeconds(5))));

        List<Check> checks = store.checks(row.id());
        assertEquals(2, checks.size());
        Check spf = checks.stream().filter(check -> check.name() == CheckName.SPF).findFirst().orElseThrow();
        assertEquals(CheckState.PASS, spf.state());
        assertEquals("SPF: v=spf1 mx -all", spf.detail());
    }

    @Test void auditSurvivesDomainDeletion() {
        String domain = uniqueDomain();
        var row = store.create(domain, UUID.randomUUID());
        store.recordEvent(row.id(), row.domain(), UUID.randomUUID(), "registered", null);
        store.recordEvent(row.id(), row.domain(), UUID.randomUUID(), "deleted", "реестр");
        assertTrue(store.delete(domain));
        Integer events = jdbc.queryForObject("SELECT count(*) FROM mail_domain_events WHERE domain = ?",
                Integer.class, domain);
        assertEquals(2, events);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM mail_domain_events WHERE domain_id = ?", Integer.class, row.id()));
    }
}
