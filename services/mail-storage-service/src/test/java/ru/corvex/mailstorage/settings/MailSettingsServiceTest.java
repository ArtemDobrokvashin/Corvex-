package ru.corvex.mailstorage.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSettingsServiceTest {

    private final UUID ownerId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private MailSettingsService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new MailSettingsService(jdbc, new ObjectMapper());
    }

    @Test
    void returnsDefaultsForANewUserWithoutWritingAnything() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        MailSettings settings = service.get(ownerId);

        // Умолчания совпадают с фронтендом: первый вход не должен менять вид списка.
        assertEquals(MailSettings.defaults(), settings);
        assertEquals("date-desc", settings.defaultSort());
        assertTrue(settings.conversations());
    }

    @Test
    void rejectsGarbageInsteadOfStoringIt() {
        MailSettings normalized = MailSettingsService.normalize(new MailSettings(
                "огромная", true, true, "sometimes", "по-алфавиту", "я".repeat(9000), true, false, false, false));

        assertEquals("comfortable", normalized.density());
        assertEquals("automatic", normalized.markRead());
        assertEquals("date-desc", normalized.defaultSort());
        // Подпись обрезается, а не роняет сохранение.
        assertEquals(MailSettings.MAX_SIGNATURE, normalized.signature().length());
    }

    @Test
    void saveKeepsTheValuesTheInterfaceSends() {
        MailSettings saved = service.save(ownerId, new MailSettings("compact", false, false, "manual",
                "unread-first", "Пётр", true, true, false, false, true));

        verify(jdbc).update(anyString(), any(Object[].class));
        assertEquals("compact", saved.density());
        assertFalse(saved.showPreview());
        assertTrue(saved.signatureForSubmission());
    }
}
