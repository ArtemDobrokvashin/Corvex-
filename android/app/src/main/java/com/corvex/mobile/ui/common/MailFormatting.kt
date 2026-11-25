package com.corvex.mobile.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Форматирование того, что видно в списке писем. Логика повторяет веб-интерфейс
 * и вынесена в чистые функции: их можно проверить юнит-тестом без эмулятора, а
 * эмулятора у нас пока нет.
 */
object MailFormatting {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))
    private val dayMonthFormatter = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))

    /**
     * Время в списке: сегодня — «14:05», в этом году — «3 мар», раньше — «03.03.25».
     * Абсолютное время без года сбивает с толку, поэтому старые письма его получают.
     */
    fun listTimestamp(
        receivedAtMillis: Long,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        if (receivedAtMillis <= 0L) return ""
        val moment = Instant.ofEpochMilli(receivedAtMillis).atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return when {
            moment.toLocalDate() == today -> timeFormatter.format(moment)
            moment.year == today.year -> dayMonthFormatter.format(moment)
            else -> String.format(
                Locale.ROOT,
                "%02d.%02d.%02d",
                moment.dayOfMonth,
                moment.monthValue,
                moment.year % 100
            )
        }
    }

    /**
     * Отправитель: «Анна Петрова <anna@company.local>» превращается в «Анна Петрова».
     * В списке важнее имя, а адрес остаётся в открытом письме.
     */
    fun displayName(sender: String?): String {
        val raw = sender?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val angle = raw.indexOf('<')
        if (angle > 0) {
            val name = raw.substring(0, angle).trim().trim('"')
            if (name.isNotEmpty()) return name
        }
        return raw.substringAfter('<').substringBefore('>').ifBlank { raw }
    }

    /** Первая буква имени для аватара; пустая строка, если имени нет. */
    fun initial(sender: String?): String =
        displayName(sender).firstOrNull()?.uppercaseChar()?.toString().orEmpty()

    /** Превью письма в одну строку: переводы строк и повторные пробелы схлопываются. */
    fun snippet(text: String?, subject: String?, limit: Int = 110): String {
        val body = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val fallback = subject?.trim().orEmpty()
        val chosen = body.ifEmpty { fallback }
        return if (chosen.length <= limit) chosen else chosen.take(limit).trimEnd() + "…"
    }

    /** «3 письма», «1 письмо», «5 писем» — счётчик в заголовке папки. */
    fun letters(count: Int): String {
        val tail = when {
            count % 10 == 1 && count % 100 != 11 -> "письмо"
            count % 10 in 2..4 && count % 100 !in 12..14 -> "письма"
            else -> "писем"
        }
        return "$count $tail"
    }

    /** Сегодняшняя дата нужна только тестам и отладке. */
    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate = LocalDate.now(zone)
}
