package ru.corvex.mailstorage.draft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebmailDraftStoreTest {
    @Test void unknownOrForeignDraftIsNotFound() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebmailDraftStore store = new WebmailDraftStore(jdbc, new ObjectMapper());
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<WebmailDraftStore.Draft>>any(), any(), any())).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class, () -> store.get(UUID.randomUUID(), UUID.randomUUID())).getStatusCode());
    }
    @Test void validatesBeforeWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebmailDraftStore store = new WebmailDraftStore(jdbc, new ObjectMapper());
        DraftPayload bad = new DraftPayload("", "", "x".repeat(999), "", "", null, List.of(), null, null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> store.create(UUID.randomUUID(), bad)).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void rejectsInvalidRevision() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebmailDraftStore store = new WebmailDraftStore(jdbc, new ObjectMapper());
        DraftPayload payload = new DraftPayload("", "", "", "body", "", null, List.of(), null, null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class, () -> store.update(UUID.randomUUID(), UUID.randomUUID(), 0, payload)).getStatusCode());
    }
}
