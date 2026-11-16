package ru.corvex.mailstorage.calendar;

import org.junit.jupiter.api.BeforeAll;
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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ограничения схемы календаря проверяются на настоящем PostgreSQL: именно БД —
 * последняя линия защиты, если в API появится другой клиент или ошибка в коде.
 */
@Testcontainers(disabledWithoutDocker = true)
class CalendarSchemaPostgresIT {

    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:16.9"))
            .withEnv("POSTGRES_USER", "synthetic")
            .withEnv("POSTGRES_PASSWORD", "synthetic")
            .withEnv("POSTGRES_DB", "synthetic")
            .withExposedPorts(5432)
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                    .forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    static JdbcTemplate jdbc;

    @BeforeAll
    static void schema() {
        var data = new DriverManagerDataSource("jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/synthetic", "synthetic", "synthetic");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V8__calendar.sql")).execute(data);
        jdbc = new JdbcTemplate(data);
    }

    private static java.sql.Timestamp ts(String instant) {
        return java.sql.Timestamp.from(Instant.parse(instant));
    }

    private static UUID insertEvent(String title, String start, String end, Integer reminder) {
        return jdbc.queryForObject("""
                INSERT INTO calendar_events (owner_id, title, starts_at, ends_at, time_zone, reminder_minutes)
                VALUES (?, ?, ?, ?, 'Europe/Amsterdam', ?)
                RETURNING id
                """, UUID.class, UUID.randomUUID(), title, ts(start), ts(end), reminder);
    }

    @Test
    void eventRequiresEndAfterStart() {
        assertThrows(DataIntegrityViolationException.class, () -> insertEvent("Backwards",
                "2026-10-19T10:00:00Z", "2026-10-19T09:00:00Z", null));
    }

    @Test
    void reminderOutsideSevenDaysIsRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> insertEvent("Too early reminder",
                "2026-10-19T10:00:00Z", "2026-10-19T11:00:00Z", 20_000));
    }

    @Test
    void mailSourceWithoutMailIdIsRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO calendar_events (owner_id, title, starts_at, ends_at, time_zone, source_kind)
                VALUES (?, 'From mail', ?, ?, 'UTC', 'MAIL')
                """, UUID.randomUUID(), ts("2026-10-19T10:00:00Z"), ts("2026-10-19T11:00:00Z")));
    }

    @Test
    void oneReminderPerOccurrence() {
        UUID eventId = insertEvent("Planning",
                "2026-10-19T10:00:00Z", "2026-10-19T11:00:00Z", 15);
        Instant occurrence = Instant.parse("2026-10-19T10:00:00Z");
        jdbc.update("""
                INSERT INTO calendar_reminder_deliveries (event_id, owner_id, occurrence_at, due_at)
                VALUES (?, ?, ?, ?)
                """, eventId, UUID.randomUUID(), java.sql.Timestamp.from(occurrence),
                java.sql.Timestamp.from(occurrence.minusSeconds(900)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO calendar_reminder_deliveries (event_id, owner_id, occurrence_at, due_at)
                VALUES (?, ?, ?, ?)
                """, eventId, UUID.randomUUID(), java.sql.Timestamp.from(occurrence),
                java.sql.Timestamp.from(occurrence.minusSeconds(900))));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM calendar_reminder_deliveries WHERE event_id = ?", Integer.class, eventId));
    }
}
