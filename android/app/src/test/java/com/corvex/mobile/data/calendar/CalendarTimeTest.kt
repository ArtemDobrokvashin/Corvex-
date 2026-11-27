package com.corvex.mobile.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Перевод времени и разворот повторений.
 *
 * Ошибки здесь не падают: событие просто оказывается не в тот день или на час
 * раньше. Поэтому проверяем края — границу летнего времени, 31-е число,
 * недельный шаг.
 */
class CalendarTimeTest {

    private class Stub(
        override val date: LocalDate,
        override val recurrence: String
    ) : CalendarTime.CalendarEventLike

    @Test
    fun `local time is converted with the zone offset of that moment`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // Зимой Берлин +01:00: 09:30 местного = 08:30 UTC.
        assertEquals(
            "2026-01-15T08:30:00Z",
            CalendarTime.toInstant(LocalDate.of(2026, 1, 15), LocalTime.of(9, 30), berlin)
        )
        // Летом +02:00: то же местное время — это уже 07:30 UTC. Клиент,
        // который считает смещение один раз, ошибается ровно здесь.
        assertEquals(
            "2026-07-15T07:30:00Z",
            CalendarTime.toInstant(LocalDate.of(2026, 7, 15), LocalTime.of(9, 30), berlin)
        )
    }

    @Test
    fun `instant is shown in the zone of the event, not the device`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val (date, time) = CalendarTime.toZoned("2026-10-19T00:00:00Z", tokyo)!!
        // 00:00 UTC — это 09:00 в Токио того же дня.
        assertEquals(LocalDate.of(2026, 10, 19), date)
        assertEquals(LocalTime.of(9, 0), time)
    }

    @Test
    fun `offset timestamps are accepted too`() {
        val (date, time) = CalendarTime.toZoned("2026-10-19T12:00:00+03:00", ZoneId.of("UTC"))!!
        assertEquals(LocalDate.of(2026, 10, 19), date)
        assertEquals(LocalTime.of(9, 0), time)
    }

    @Test
    fun `broken instant gives null instead of crash`() {
        assertNull(CalendarTime.toZoned("не дата", ZoneId.of("UTC")))
        assertEquals(0L, CalendarTime.epochMillis("не дата"))
        assertEquals(0L, CalendarTime.epochMillis(null))
    }

    @Test
    fun `non recurring event appears only on its own day`() {
        val event = Stub(LocalDate.of(2026, 10, 19), "none")
        val days = CalendarTime.occurrences(
            event,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31)
        ).map { it.first }

        assertEquals(listOf(LocalDate.of(2026, 10, 19)), days)
    }

    @Test
    fun `event outside the window is not shown`() {
        val event = Stub(LocalDate.of(2026, 9, 1), "none")
        assertTrue(
            CalendarTime.occurrences(event, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
                .isEmpty()
        )
    }

    @Test
    fun `weekdays rule skips the weekend`() {
        val event = Stub(LocalDate.of(2026, 10, 19), "weekdays")
        val days = CalendarTime.occurrences(
            event,
            LocalDate.of(2026, 10, 19),
            LocalDate.of(2026, 10, 25)
        ).map { it.first }

        // 19–23 октября — будни, 24 и 25 — суббота и воскресенье.
        assertEquals(5, days.size)
        assertTrue(days.none { it.dayOfWeek.value >= 6 })
    }

    @Test
    fun `weekly rule keeps the weekday of the first occurrence`() {
        val event = Stub(LocalDate.of(2026, 10, 19), "weekly")
        val days = CalendarTime.occurrences(
            event,
            LocalDate.of(2026, 10, 19),
            LocalDate.of(2026, 11, 16)
        ).map { it.first }

        assertTrue(days.all { it.dayOfWeek == LocalDate.of(2026, 10, 19).dayOfWeek })
        assertEquals(5, days.size)
    }

    @Test
    fun `monthly rule does not invent the 31st`() {
        // 31 января: в феврале 31-го нет, и переносить на 28-е нельзя —
        // это была бы другая встреча.
        val event = Stub(LocalDate.of(2026, 1, 31), "monthly")
        val days = CalendarTime.occurrences(
            event,
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 5, 31)
        ).map { it.first }

        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31)),
            days
        )
    }

    @Test
    fun `occurrences never start before the event itself`() {
        val event = Stub(LocalDate.of(2026, 10, 19), "daily")
        val days = CalendarTime.occurrences(
            event,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 3)
        )
        assertTrue(days.isEmpty())
    }

    @Test
    fun `window covers whole months`() {
        val (from, to) = CalendarTime.window(java.time.YearMonth.of(2026, 2))
        assertEquals(LocalDate.of(2026, 1, 1), from)
        assertEquals(LocalDate.of(2026, 3, 31), to)
    }
}
