package com.corvex.mobile.domain.calendar

import com.corvex.mobile.data.calendar.CalendarTime
import java.time.LocalDate
import java.time.LocalTime

/**
 * Событие календаря.
 *
 * Хранится в двух видах одновременно, и это не дублирование:
 *
 *  - `startsAt` / `endsAt` — моменты времени в UTC, ровно как на сервере;
 *  - `date` / `startTime` / `endTime` — то же самое, но в зоне события, для
 *    сетки календаря. Пересчитывать их каждый раз при отрисовке значило бы
 *    разбирать ISO-строки в каждом кадре списка.
 */
data class CalendarEvent(
    val id: String?,
    val title: String,
    val description: String,
    val location: String,
    val startsAt: String,
    val endsAt: String,
    val allDay: Boolean,
    val timeZone: String,
    val priority: String,
    val busyStatus: String,
    // `override` обязателен: свойство объявлено в CalendarTime.CalendarEventLike,
    // и без него компилятор отвечает «hides member of supertype and needs
    // 'override' modifier». Пока его не было, не собирался весь модуль.
    override val recurrence: String,
    val reminderMinutes: Int?,
    val attendees: List<String>,
    val revision: Long,
    override val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime
) : CalendarTime.CalendarEventLike {
    /** Событие, которое ещё не сохранялось на сервере. */
    val isNew: Boolean get() = id.isNullOrBlank()

    val recurrenceLabel: String
        get() = when (recurrence) {
            "daily" -> "Каждый день"
            "weekdays" -> "По будням"
            "weekly" -> "Каждую неделю"
            "monthly" -> "Каждый месяц"
            else -> ""
        }
}

/** Черновик события из диалога. Время уже пересчитано в UTC. */
data class CalendarDraft(
    val title: String,
    val description: String = "",
    val location: String = "",
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val allDay: Boolean = false,
    val timeZone: String,
    val recurrence: String = "none",
    val reminderMinutes: Int? = null,
    val priority: String = "medium",
    val attendees: List<String> = emptyList()
)
