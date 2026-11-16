package ru.corvex.mailstorage.calendar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarRecurrenceTest {

    private static Instant at(String date, String time, String zone) {
        return ZonedDateTime.of(
                java.time.LocalDate.parse(date),
                java.time.LocalTime.parse(time),
                ZoneId.of(zone)).toInstant();
    }

    @Test
    void dailyEventKeepsLocalTimeAcrossDaylightSaving() {
        Instant first = at("2026-03-28", "09:00", "Europe/Amsterdam");
        List<Instant> occurrences = CalendarRecurrence.occurrences("daily", first, "Europe/Amsterdam",
                at("2026-03-28", "00:00", "Europe/Amsterdam"),
                at("2026-03-31", "00:00", "Europe/Amsterdam"));

        assertEquals(3, occurrences.size());
        for (Instant occurrence : occurrences) {
            assertEquals(java.time.LocalTime.of(9, 0),
                    occurrence.atZone(ZoneId.of("Europe/Amsterdam")).toLocalTime());
        }
        // 28 марта ещё UTC+1, 30 марта уже UTC+2: момент старта сдвигается,
        // а местное время остаётся 09:00.
        assertEquals(Instant.parse("2026-03-28T08:00:00Z"), occurrences.get(0));
        assertEquals(Instant.parse("2026-03-30T07:00:00Z"), occurrences.get(2));
    }

    @Test
    void weekdaysSkipSaturdayAndSunday() {
        Instant first = at("2026-10-16", "10:00", "UTC"); // пятница
        List<Instant> occurrences = CalendarRecurrence.occurrences("weekdays", first, "UTC",
                Instant.parse("2026-10-16T00:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"));

        assertEquals(List.of(
                Instant.parse("2026-10-16T10:00:00Z"),
                Instant.parse("2026-10-19T10:00:00Z")), occurrences);
    }

    @Test
    void weeklyRepeatsOnTheSameDayOfWeek() {
        Instant first = at("2026-10-19", "08:30", "UTC");
        List<Instant> occurrences = CalendarRecurrence.occurrences("weekly", first, "UTC",
                Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-11-10T00:00:00Z"));

        assertEquals(4, occurrences.size());
        assertTrue(occurrences.stream().allMatch(occurrence ->
                occurrence.atZone(ZoneId.of("UTC")).getDayOfWeek() == java.time.DayOfWeek.MONDAY));
    }

    @Test
    void monthlyOnTheThirtyFirstSkipsShortMonths() {
        Instant first = at("2026-01-31", "12:00", "UTC");
        List<Instant> occurrences = CalendarRecurrence.occurrences("monthly", first, "UTC",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-05-01T00:00:00Z"));

        // Февраль, апрель и месяцы без 31-го числа пропускаются: перенос на 1-е
        // менял бы смысл встречи.
        assertEquals(List.of(
                Instant.parse("2026-01-31T12:00:00Z"),
                Instant.parse("2026-03-31T12:00:00Z")), occurrences);
    }

    @Test
    void nonRecurringEventAppearsOnlyInsideTheRequestedWindow() {
        Instant start = Instant.parse("2026-10-19T09:00:00Z");
        assertEquals(List.of(start), CalendarRecurrence.occurrences("none", start, "UTC",
                Instant.parse("2026-10-19T00:00:00Z"), Instant.parse("2026-10-20T00:00:00Z")));
        assertTrue(CalendarRecurrence.occurrences("none", start, "UTC",
                Instant.parse("2026-10-20T00:00:00Z"), Instant.parse("2026-10-21T00:00:00Z")).isEmpty());
    }
}
