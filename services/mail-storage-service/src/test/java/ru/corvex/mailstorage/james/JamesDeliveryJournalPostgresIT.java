package ru.corvex.mailstorage.james;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated PostgreSQL only; this does not contact James or deliver mail. */
@Testcontainers(disabledWithoutDocker = true)
class JamesDeliveryJournalPostgresIT {
    @Container static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:16.9"))
            .withEnv("POSTGRES_USER", "synthetic").withEnv("POSTGRES_PASSWORD", "synthetic")
            .withEnv("POSTGRES_DB", "synthetic").withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    static JdbcTemplate jdbc;
    static JamesDeliveryJournal journal;
    static DataSourceTransactionManager transactions;
    private static final String HASH = "a".repeat(64);

    @BeforeAll static void setup() {
        var data = new DriverManagerDataSource("jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/synthetic", "synthetic", "synthetic");
        new ResourceDatabasePopulator(new ClassPathResource("james-lab-schema/delivery_journal.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
        transactions = new DataSourceTransactionManager(data);
        journal = new JamesDeliveryJournal(jdbc, transactions);
    }

    @Test void repeatedReservationMatchesImmutableInputsAndNeverAllowsResubmission() {
        String raw = UUID.randomUUID().toString(); UUID owner = UUID.randomUUID();
        var reserved = journal.reserve(raw, owner, "account", "inbox", HASH, 20);
        assertEquals("reserved", reserved.phase());
        assertEquals(reserved.intent(), journal.reserve(raw, owner, "account", "inbox", HASH, 20).intent());
        assertThrows(IllegalStateException.class, () -> journal.reserve(raw, owner, "account", "inbox", HASH, 21));
        assertThrows(IllegalStateException.class, () -> journal.reserve(raw, owner, "other", "inbox", HASH, 20));
        var ticket = journal.begin(reserved.intent());
        assertEquals("submitting", journal.reserve(raw, owner, "account", "inbox", HASH, 20).phase());
        assertThrows(IllegalStateException.class, () -> journal.begin(reserved.intent()));
        assertThrows(SecurityException.class, () -> new JamesDeliveryJournal(jdbc, transactions).confirm(ticket, "remote"));
        journal.confirm(ticket, "remote");
        assertThrows(SecurityException.class, () -> journal.confirm(ticket, "remote"));
        assertEquals("confirmed", journal.reserve(raw, owner, "account", "inbox", HASH, 20).phase());
    }

    @Test void crashAndAmbiguousResponsesStayBlocked() {
        var intent = journal.reserve(UUID.randomUUID().toString(), UUID.randomUUID(), "account", "inbox", HASH, 0).intent();
        var ticket = journal.begin(intent);
        var restarted = new JamesDeliveryJournal(jdbc, transactions);
        assertThrows(IllegalStateException.class, () -> restarted.begin(intent));
        journal.uncertain(ticket);
        assertThrows(IllegalStateException.class, () -> restarted.begin(intent));
        assertThrows(SecurityException.class, () -> journal.confirm(ticket, "remote"));
    }

    @Test void concurrentBeginHasOnlyOneWinner() throws Exception {
        var intent = journal.reserve(UUID.randomUUID().toString(), UUID.randomUUID(), "account", "inbox", HASH, 4).intent();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> { start.await(); try { journal.begin(intent); return true; } catch (IllegalStateException e) { return false; } });
            var two = pool.submit(() -> { start.await(); try { journal.begin(intent); return true; } catch (IllegalStateException e) { return false; } });
            start.countDown();
            assertNotEquals(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            assertEquals("submitting", jdbc.queryForObject("SELECT phase FROM corvex_james_delivery_intent WHERE operation_id = ?", String.class, intent.operationId()));
        } finally { pool.shutdownNow(); }
    }
}
