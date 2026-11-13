package ru.corvex.mailstorage.james;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated PostgreSQL schema/registry test. No real accounts or James server. */
@Testcontainers(disabledWithoutDocker = true)
class JamesRegistryPostgresIT {
    @Container static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:16.9"))
            .withEnv("POSTGRES_USER", "synthetic")
            .withEnv("POSTGRES_PASSWORD", "synthetic")
            .withEnv("POSTGRES_DB", "synthetic")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @Test void migrationCreatesEmptyDisabledUniqueBindings() {
        var data = new DriverManagerDataSource(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/synthetic",
                "synthetic", "synthetic");
        new ResourceDatabasePopulator(new ClassPathResource("james-lab-schema/mailbox_bindings.sql")).execute(data);
        var jdbc = new JdbcTemplate(data);
        var registry = new JdbcJamesMailboxRegistry(jdbc);
        UUID owner = UUID.randomUUID();
        assertTrue(registry.find(owner).isEmpty());
        jdbc.update("INSERT INTO corvex_james_mailbox_binding (owner_id, james_login, account_id) VALUES (?, ?, ?)",
                owner, "User@james.local", "account-one");
        var binding = registry.find(owner).orElseThrow();
        assertEquals(owner, binding.ownerId());
        assertFalse(binding.active());
        assertEquals("User@james.local", binding.jamesLogin());
        assertEquals("account-one", binding.accountId());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO corvex_james_mailbox_binding (owner_id, james_login, account_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), "user@james.local", "account-two"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO corvex_james_mailbox_binding (owner_id, james_login, account_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), "different@james.local", "account-one"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO corvex_james_mailbox_binding (owner_id, james_login, account_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), " ", "account-three"));
    }
}
