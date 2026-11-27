package com.corvex.mobile.data

import java.time.Instant
import java.time.OffsetDateTime

/**
 * Разбор времени ISO-8601 из ответов сервера.
 *
 * `Instant.parse` понимает `2026-11-26T10:00:00Z`, но не выдерживает
 * смещения вида `+03:00` — поэтому `OffsetDateTime` как запасной вариант.
 * Ноль вместо исключения: одно кривое время не должно ронять весь список.
 *
 * Лежит в общем пакете, потому что нужен чату, диску и админке: держать
 * копию в каждом репозитории — это три места, где можно разойтись.
 */
fun parseInstant(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    return runCatching { Instant.parse(value).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}
