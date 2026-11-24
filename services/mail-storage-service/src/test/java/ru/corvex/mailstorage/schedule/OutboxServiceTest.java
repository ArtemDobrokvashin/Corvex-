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
    void cancelRefusesWhenTheMessageIsAlreadySending() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        assertFalse(service.cancel(ownerId, UUID.randomUUID()));
        verify(spool, never()).delete(anyString());
    }
}
