package com.corvex.mobile.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Разбор того, что человек ввёл руками.
 *
 * Здесь ошибка выглядит как «сохранил, а событие не появилось» или «время
 * съехало»: сервер справедливо ответит 400, а человек не поймёт, что не так.
 * Поэтому строки проверяются до отправки.
 */
class EventEditorParsingTest {

    @Test
    fun `time is parsed from common forms`() {
        assertEquals(LocalTime.of(9, 30), parseTime("09:30"))
        assertEquals(LocalTime.of(9, 30), parseTime("9:30"))
        assertEquals(LocalTime.of(9, 30), parseTime("9.30"))
        assertEquals(LocalTime.of(9, 0), parseTime("9"))
        assertEquals(LocalTime.of(0, 0), parseTime("00:00"))
        assertEquals(LocalTime.of(23, 59), parseTime("23:59"))
    }

    @Test
    fun `impossible time is rejected`() {
        assertNull(parseTime("25:00"))
        assertNull(parseTime("9:75"))
        assertNull(parseTime(""))
        assertNull(parseTime("вечером"))
        assertNull(parseTime("12:00:00"))
    }

    @Test
    fun `date must be ISO`() {
        assertEquals(LocalDate.of(2026, 10, 19), parseDate("2026-10-19"))
        // Русский формат «19.10.2026» отклоняется осознанно: 10.11.2026 и
        // 11.10.2026 неразличимы без соглашения, а сервер ждёт ISO.
        assertNull(parseDate("19.10.2026"))
        assertNull(parseDate("2026-13-01"))
        assertNull(parseDate(""))
    }

    @Test
    fun `reminder accepts a week and rejects more`() {
        assertEquals(15, reminderMinutes("15"))
        assertEquals(0, reminderMinutes("0"))
        assertEquals(10_080, reminderMinutes("10080"))
        assertNull(reminderMinutes("10081"))
        assertNull(reminderMinutes("-5"))
        assertNull(reminderMinutes(""))
        assertNull(reminderMinutes("час"))
    }

    @Test
    fun `recurrence labels match the server rules`() {
        // Список правил — ровно серверный CalendarRecurrence.RULES.
        listOf("none", "daily", "weekdays", "weekly", "monthly").forEach { rule ->
            val label = recurrenceLabelOf(rule)
            assertEquals("нет правила «$rule» без подписи", true, label.isNotBlank())
        }
        assertEquals("нет", recurrenceLabelOf("none"))
        assertEquals("по будням", recurrenceLabelOf("weekdays"))
    }
}
