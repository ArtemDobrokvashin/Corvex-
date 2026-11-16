package ru.corvex.mailstorage.calendar;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalendarServiceRulesTest {

    @Test
    void attendeesAreTrimmedDeduplicatedAndKeepTheirOriginalSpelling() {
        List<String> result = CalendarService.normalizeAttendees(
                List.of(" i.ivanov@company.local ", "", "I.Ivanov@Company.Local", "Пётр Петров"));
        assertEquals(List.of("i.ivanov@company.local", "Пётр Петров"), result);
    }

    @Test
    void fiftyOneAttendeesAreRejected() {
        List<String> attendees = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            attendees.add("user" + i + "@company.local");
        }
        assertThrows(ResponseStatusException.class, () -> CalendarService.normalizeAttendees(attendees));
    }

    @Test
    void controlCharactersInAttendeeAreRejected() {
        assertThrows(ResponseStatusException.class,
                () -> CalendarService.normalizeAttendees(List.of("bad\naddress@company.local")));
    }

    @Test
    void retryBackoffGrowsAndIsCapped() {
        assertEquals(Duration.ofMinutes(1), CalendarReminderScheduler.backoff(1));
        assertEquals(Duration.ofMinutes(2), CalendarReminderScheduler.backoff(2));
        assertEquals(Duration.ofMinutes(4), CalendarReminderScheduler.backoff(3));
        assertEquals(Duration.ofMinutes(8), CalendarReminderScheduler.backoff(4));
        assertEquals(Duration.ofMinutes(8), CalendarReminderScheduler.backoff(9));
    }
}
