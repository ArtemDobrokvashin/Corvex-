package ru.corvex.mailstorage.schedule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import ru.corvex.mailstorage.pipeline.MailEventPublisher;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentMatchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxServiceTest {

    private final UUID ownerId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private RawMessageSpool spool;
    private MailEventPublisher events;
    private OutboxService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        spool = mock(RawMessageSpool.class);
        events = mock(MailEventPublisher.class);
        service = new OutboxService(jdbc, spool, events);
    }

    @Test
    void rejectsTimesInThePastAndBeyondAYear() {
        RawMessageSpool.StoredRawMessage raw = new RawMessageSpool.StoredRawMessage("raw-1", "sha", 10);

        assertThrows(IllegalArgumentException.class, () -> service.schedule(ownerId, raw, List.of(), List.of("a@b"),
                "me@x", "<m@x>", null, "S", true, Instant.now().minusSeconds(60)));
        assertThrows(IllegalArgumentException.class, () -> service.schedule(ownerId, raw, List.of(), List.of("a@b"),
                "me@x", "<m@x>", null, "S", true, Instant.now().plusSeconds(500L * 24 * 3600)));

        // Опечатка в дате не должна тихо поставить письмо в очередь.
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void acceptsAFutureTimeWithinTheHorizon() {
        RawMessageSpool.StoredRawMessage raw = new RawMessageSpool.StoredRawMessage("raw-1", "sha", 10);

        UUID id = service.schedule(ownerId, raw, List.of("peer@company.local"), List.of("a@b"),
                "me@x", "<m@x>", null, "S", true, Instant.now().plusSeconds(3600));

        assertNotNull(id);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void cancelRefusesWhenTheMessageIsAlreadySending() {
        // Отменять нечего: строка уже не в SCHEDULED. Возврат false — это ответ
        // «уже поздно», а не ошибка, и спул трогать нельзя.
        service = new OutboxService(outbox(0, List.of()), spool, events);

        assertFalse(service.cancel(ownerId, UUID.randomUUID()));
        verify(spool, never()).delete(anyString());
    }

    @Test
    void cancelDeletesTheSpoolCopyOfAScheduledMessage() throws Exception {
        doReturn(List.of("raw-1")).when(jdbc)
                .query(anyString(), ArgumentMatchers.<RowMapper<String>>any(), any(Object[].class));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertTrue(service.cancel(ownerId, UUID.randomUUID()));

        verify(spool).delete("raw-1");
    }

    @Test
    void dispatchOfAMissingRowSendsNothing() {
        // Строки нет: письмо уже ушло или отменено. Ничего не публикуем.
        service = new OutboxService(outbox(0, List.of()), spool, events);

        service.dispatch(UUID.randomUUID());

        verify(events, never()).publishConfirmed(anyString(), any(), anyString());
    }

    /**
     * Заглушка JdbcTemplate без Mockito: у query() четыре перегруза, и тест не
     * должен зависеть от того, какой из них выберет компилятор.
     */
    private static JdbcTemplate outbox(int updated, List<String> refs) {
        return new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                return (List<T>) refs;
            }

            @Override
            public int update(String sql, Object... args) {
                return updated;
            }
        };
    }
}
