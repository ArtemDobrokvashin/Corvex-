package ru.corvex.mailstorage.james;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated PostgreSQL only: no James account is created and no mail is touched. */
@Testcontainers(disabledWithoutDocker = true)
class JamesProvisioningJournalPostgresIT {
    @Container static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:16.9"))
            .withEnv("POSTGRES_USER", "synthetic").withEnv("POSTGRES_PASSWORD", "synthetic")
            .withEnv("POSTGRES_DB", "synthetic").withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
    static JdbcTemplate jdbc;
    static JamesProvisioningJournal journal;
    static DataSourceTransactionManager transactions;

    @BeforeAll static void schema() {
        var data = new DriverManagerDataSource("jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/synthetic", "synthetic", "synthetic");
        new ResourceDatabasePopulator(new ClassPathResource("james-lab-schema/mailbox_bindings.sql"),
                new ClassPathResource("james-lab-schema/provisioning_journal.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
        transactions = new DataSourceTransactionManager(data);
        journal = new JamesProvisioningJournal(jdbc, transactions);
    }

    private JamesProvisioningJournal.Intent reserved() { return journal.reserve(UUID.randomUUID(), UUID.randomUUID()); }
    private String phase(JamesProvisioningJournal.Intent intent) {
        return jdbc.queryForObject("SELECT phase FROM corvex_james_provisioning WHERE operation_id = ?", String.class, intent.operationId());
    }

    @Test void normalFlowCreatesDisabledBindingAndAudit() {
        var intent = reserved();
        assertTrue(intent.login().matches("cvx-[0-9a-f]{32}@localhost"));
        var submitted = journal.beginCreate(intent);
        assertTrue(journal.findCreated(intent.operationId(), intent.actorId()).isEmpty());
        journal.confirmCreate(submitted);
        assertEquals(intent, journal.findCreated(intent.operationId(), intent.actorId()).orElseThrow());
        assertTrue(journal.findCreated(intent.operationId(), UUID.randomUUID()).isEmpty());
        journal.recordBinding(intent, "account-" + intent.operationId());
        assertTrue(journal.findCreated(intent.operationId(), intent.actorId()).isEmpty());
        assertEquals("bound", phase(intent));
        var mapping = new JdbcJamesMailboxRegistry(jdbc).find(intent.ownerId()).orElseThrow();
        assertFalse(mapping.active());
        assertEquals(intent.login(), mapping.jamesLogin());
        assertEquals(4, jdbc.queryForObject("SELECT count(*) FROM corvex_james_provisioning_event WHERE operation_id = ?", Integer.class, intent.operationId()));
    }

    @Test void uncertainOrInterruptedSubmissionCannotBeRetriedOrBound() {
        var interrupted = reserved();
        var submitted = journal.beginCreate(interrupted);
        assertThrows(IllegalStateException.class, () -> journal.beginCreate(interrupted));
        assertThrows(IllegalStateException.class, () -> journal.recordBinding(interrupted, "account"));
        journal.markUncertain(interrupted);
        assertThrows(IllegalStateException.class, () -> journal.beginCreate(interrupted));
        assertThrows(IllegalStateException.class, () -> journal.confirmCreate(submitted));
        assertThrows(IllegalStateException.class, () -> journal.recordBinding(interrupted, "account"));
        assertEquals("uncertain", phase(interrupted));
    }

    @Test void aNewJournalInstanceCannotConfirmAnOldInFlightSubmission() {
        var intent = reserved();
        var inFlight = journal.beginCreate(intent);
        var restarted = new JamesProvisioningJournal(jdbc, transactions);
        assertThrows(SecurityException.class, () -> restarted.confirmCreate(inFlight));
        assertThrows(IllegalStateException.class, () -> restarted.beginCreate(intent));
        assertEquals("submitting", phase(intent));
    }

    @Test void duplicateOwnerOrAccountNeverReplacesMappingAndRollsBackState() {
        var first = reserved();
        assertThrows(DataIntegrityViolationException.class, () -> journal.reserve(first.ownerId(), UUID.randomUUID()));
        var firstSubmission = journal.beginCreate(first); journal.confirmCreate(firstSubmission); journal.recordBinding(first, "shared-account");
        var second = reserved(); var secondSubmission = journal.beginCreate(second); journal.confirmCreate(secondSubmission);
        assertThrows(DataIntegrityViolationException.class, () -> journal.recordBinding(second, "shared-account"));
        assertEquals("created", phase(second));
        assertTrue(new JdbcJamesMailboxRegistry(jdbc).find(second.ownerId()).isEmpty());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM corvex_james_provisioning_event WHERE operation_id = ?", Integer.class, second.operationId()));
    }

    @Test void alteredIntentCannotAdvanceAnotherOperation() {
        var original = reserved();
        var forged = new JamesProvisioningJournal.Intent(original.operationId(), original.ownerId(), UUID.randomUUID(), original.login());
        assertThrows(IllegalStateException.class, () -> journal.beginCreate(forged));
        assertEquals("reserved", phase(original));
    }

    @Test void concurrentSubmissionHasExactlyOneWinner() throws Exception {
        var intent = reserved();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> attempt(intent, start));
            var two = pool.submit(() -> attempt(intent, start));
            start.countDown();
            assertNotEquals(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
            assertEquals("submitting", phase(intent));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM corvex_james_provisioning_event WHERE operation_id = ?", Integer.class, intent.operationId()));
        } finally { pool.shutdownNow(); }
    }
    private static boolean attempt(JamesProvisioningJournal.Intent intent, CountDownLatch start) throws InterruptedException {
        start.await();
        try { journal.beginCreate(intent); return true; }
        catch (IllegalStateException e) { return false; }
    }
}
