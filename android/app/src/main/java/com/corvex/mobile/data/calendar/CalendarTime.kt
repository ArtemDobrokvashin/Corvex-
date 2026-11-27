package com.corvex.mobile.data.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Перевод «дата + время в зоне» ↔ ISO instant.
 *
 * Логика повторяет веб-клиент (`zonedToInstant`/`instantToZoned`): смещение
 * берётся на сам момент, а не на «сегодня». Это важно у границы перехода на
 * летнее время — иначе событие в 09:00 один день в году показалось бы в 08:00
 * или 10:00.
 *
 * Все функции — чистые: их можно проверить юнит-тестом без телефона, и именно
 * здесь ошибиться проще всего.
 */
object CalendarTime {

    /** Зона устройства. `null` в теории возможно на кривой прошивке. */
    fun deviceZone(): ZoneId = runCatching { ZoneId.systemDefault() }.getOrDefault(ZoneId.of("UTC"))

    /**
     * «2026-10-19» + «09:30» в зоне → ISO instant (UTC).
     *
     * `atZone` сам учитывает смещение на нужную дату: `ZonedDateTime.of`
     * разрешает и переход на летнее время (несуществующее время сдвигается
     * вперёд, как в java.time).
     */
    fun toInstant(date: LocalDate, time: LocalTime, zone: ZoneId): String =
        ZonedDateTime.of(date, time, zone).toInstant().toString()

    /** ISO instant → дата и время в зоне события. */
    fun toZoned(instant: String, zone: ZoneId): Pair<LocalDate, LocalTime>? = runCatching {
        val moment = Instant.parse(instant).atZone(zone)
        moment.toLocalDate() to moment.toLocalTime()
    }.recoverCatching {
        // Сервер отдаёт `Z`, но старые записи могут прийти со смещением.
        val moment = java.time.OffsetDateTime.parse(instant).atZoneSameInstant(zone)
        moment.toLocalDate() to moment.toLocalTime()
    }.getOrNull()

    /**
     * Видимые даты события в диапазоне.
     *
     * Повторяющиеся события сервер отдаёт одной записью с самым первым
     * началом. Клиент разворачивает вхождения сам — ровно теми же правилами,
     * что `CalendarRecurrence` (daily/weekdays/weekly/monthly), и по
     * локальному времени зоны события: «каждые 24 часа» на переходе времени
     * сдвинуло бы встречу на час.
     *
     * @return пары «дата вхождения → событие», по одной на каждое вхождение.
     */
    fun occurrences(
        event: CalendarEventLike,
        from: LocalDate,
        to: LocalDate
    ): List<Pair<LocalDate, CalendarEventLike>> {
        val rule = event.recurrence
        if (rule.isBlank() || rule == "none") {
            return if (!event.date.isBefore(from) && !event.date.isAfter(to)) {
                listOf(event.date to event)
            } else {
                emptyList()
            }
        }
        val startDate = event.date
        // Раньше старта событие существовать не может, позже `to` — не нужно.
        var cursor = if (from.isBefore(startDate)) startDate else from
        val result = mutableListOf<Pair<LocalDate, CalendarEventLike>>()
        var guard = 0
        while (!cursor.isAfter(to) && guard < MAX_ITERATIONS) {
            if (matches(rule, startDate, cursor)) result += cursor to event
            cursor = cursor.plusDays(1)
            guard++
        }
        return result
    }

    /** Те же правила, что на сервере в `CalendarRecurrence.matches`. */
    fun matches(rule: String, start: LocalDate, date: LocalDate): Boolean = when (rule) {
        "daily" -> true
        "weekdays" -> date.dayOfWeek.value <= 5
        "weekly" -> date.dayOfWeek == start.dayOfWeek
        // 31-е число пропускает месяцы, где его нет: переносить встречу на
        // 1-е число значило бы менять её смысл без ведома владельца.
        "monthly" -> date.dayOfMonth == start.dayOfMonth
        else -> false
    }

    /**
     * Окно для запроса к серверу: сервер отдаёт события диапазоном и
     * отклоняет диапазон длиннее 400 дней.
     */
    fun window(month: YearMonth, monthsBack: Long = 1, monthsForward: Long = 1):
        Pair<LocalDate, LocalDate> = Pair(
        month.minusMonths(monthsBack).atDay(1),
        // Именно atEndOfMonth: у YearMonth нет atMonth(int), и попытка
        // «atMonth(lengthOfMonth())» не компилируется.
        month.plusMonths(monthsForward).atEndOfMonth()
    )

    /** Начало суток в ISO — то, что сервер ждёт параметром `from`. */
    fun startOfDayIso(date: LocalDate, zone: ZoneId): String =
        date.atStartOfDay(zone).toInstant().toString()

    /** Конец суток (исключающая граница) в ISO. */
    fun startOfNextDayIso(date: LocalDate, zone: ZoneId): String =
        date.plusDays(1).atStartOfDay(zone).toInstant().toString()

    private const val MAX_ITERATIONS = 800

    /** Минимум, который нужен развороту повторений: без DTO и зависимостей Retrofit. */
    interface CalendarEventLike {
        val date: LocalDate
        val recurrence: String
    }

    /** Разбор ISO-момента в миллисекунды: для сортировки и подписей. */
    fun epochMillis(instant: String?): Long {
        if (instant.isNullOrBlank()) return 0L
        return try {
            Instant.parse(instant).toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }
}
