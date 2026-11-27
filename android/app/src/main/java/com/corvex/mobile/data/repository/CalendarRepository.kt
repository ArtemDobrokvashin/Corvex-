package com.corvex.mobile.data.repository

import com.corvex.mobile.data.calendar.CalendarTime
import com.corvex.mobile.data.network.api.CalendarApi
import com.corvex.mobile.data.network.api.CalendarEventDto
import com.corvex.mobile.data.network.api.CalendarEventSaveDto
import com.corvex.mobile.data.network.api.CalendarSourceDto
import com.corvex.mobile.domain.calendar.CalendarDraft
import com.corvex.mobile.domain.calendar.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepository @Inject constructor(
    private val calendarApi: CalendarApi
) {
    /**
     * События за интервал дат.
     *
     * Границы переводятся в UTC по зоне устройства: сервер сравнивает
     * инстанты, и «с 1 октября» для него означает «с полуночи 1 октября
     * местного времени», а не UTC.
     */
    suspend fun events(from: LocalDate, to: LocalDate): Result<List<CalendarEvent>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val zone = CalendarTime.deviceZone()
                val response = calendarApi.events(
                    CalendarTime.startOfDayIso(from, zone),
                    CalendarTime.startOfNextDayIso(to, zone)
                )
                if (!response.isSuccessful) error("Не удалось загрузить события: ${response.code()}")
                response.body().orEmpty().mapNotNull { it.toDomain() }
            }
        }

    suspend fun create(draft: CalendarDraft): Result<CalendarEvent> = withContext(Dispatchers.IO) {
        runCatching {
            val response = calendarApi.create(draft.toSave())
            if (!response.isSuccessful) error(errorText(response.code(), "создать"))
            response.body()?.toDomain() ?: error("Пустой ответ сервера")
        }
    }

    /**
     * Изменение. `revision` уходит всегда: без неё сервер ответит 400, а с
     * устаревшей — 409, и это правильно (кто-то изменил событие раньше нас).
     */
    suspend fun update(event: CalendarEvent, draft: CalendarDraft): Result<CalendarEvent> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = event.id ?: error("У события нет идентификатора")
                // Занятость переносим из текущего события: редактор её не
                // меняет, а сервер заменяет событие целиком — иначе
                // «под вопросом» молча превратилось бы в «занят».
                val response = calendarApi.update(
                    id,
                    draft.toSave(revision = event.revision, busyStatus = event.busyStatus)
                )
                if (!response.isSuccessful) error(errorText(response.code(), "сохранить"))
                response.body()?.toDomain() ?: error("Пустой ответ сервера")
            }
        }

    suspend fun delete(event: CalendarEvent): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = event.id ?: error("У события нет идентификатора")
            val response = calendarApi.delete(id, event.revision)
            if (!response.isSuccessful) error(errorText(response.code(), "удалить"))
        }
    }

    private fun errorText(code: Int, action: String): String = when (code) {
        409 -> "Событие изменено в другом месте — обновите список и повторите"
        400 -> "Сервер не принял событие: проверьте дату, время и название"
        else -> "Не удалось $action событие: $code"
    }
}

/** Моменты события в UTC по зоне события, для сетки — дата и время этой зоны. */
internal fun CalendarEventDto.toDomain(): CalendarEvent? {
    val zone = runCatching { ZoneId.of(timeZone.ifBlank { "UTC" }) }.getOrDefault(ZoneId.of("UTC"))
    val (date, start) = CalendarTime.toZoned(startsAt, zone) ?: return null
    val end = CalendarTime.toZoned(endsAt, zone)?.second ?: start
    return CalendarEvent(
        id = id,
        title = title.ifBlank { "Без названия" },
        description = description.orEmpty(),
        location = location.orEmpty(),
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        timeZone = zone.id,
        priority = priority,
        busyStatus = busyStatus,
        recurrence = recurrence,
        reminderMinutes = reminderMinutes,
        attendees = attendees,
        revision = revision,
        date = date,
        // У события «на весь день» сервер хранит полночь; показывать 00:00 в
        // сетке бессмысленно, поэтому подпись отдельная.
        startTime = if (allDay) LocalTime.MIDNIGHT else start,
        endTime = if (allDay) LocalTime.of(23, 59) else end
    )
}

/** Черновик → тело запроса. Время считается в зоне устройства. */
internal fun CalendarDraft.toSave(
    revision: Long? = null,
    busyStatus: String = "busy"
): CalendarEventSaveDto {
    val zone = runCatching { ZoneId.of(timeZone) }.getOrDefault(CalendarTime.deviceZone())
    return CalendarEventSaveDto(
        title = title.trim(),
        description = description.ifBlank { null },
        location = location.ifBlank { null },
        startsAt = CalendarTime.toInstant(date, if (allDay) LocalTime.MIDNIGHT else startTime, zone),
        endsAt = CalendarTime.toInstant(date, if (allDay) LocalTime.of(23, 59) else endTime, zone),
        allDay = allDay,
        timeZone = zone.id,
        priority = priority,
        // В черновике `busyStatus` нет: редактор его не меняет. При создании
        // сервер по умолчанию поставит "busy", при правке значение приходит
        // из самого события.
        busyStatus = busyStatus,
        recurrence = recurrence,
        reminderMinutes = reminderMinutes,
        attendees = attendees,
        source = CalendarSourceDto(kind = "NONE"),
        revision = revision
    )
}
