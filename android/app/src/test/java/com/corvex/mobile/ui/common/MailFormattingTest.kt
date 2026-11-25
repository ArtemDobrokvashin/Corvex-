package com.corvex.mobile.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Юнит-тест без эмулятора: проверяет ровно те правила, что видны в списке писем.
 * Часовой пояс фиксирован, иначе тест «сегодня» зависит от машины.
 */
class MailFormattingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Moscow")

    @Test
    fun listTimestampShowsTimeForToday() {
        val now = ZonedDateTime.of(2026, 11, 25, 18, 0, 0, 0, zone).toInstant()
        val mail = ZonedDateTime.of(2026, 11, 25, 9, 5, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals("09:05", MailFormatting.listTimestamp(mail, now, zone))
    }

    @Test
    fun listTimestampShowsDayAndMonthForThisYear() {
        val now = ZonedDateTime.of(2026, 11, 25, 18, 0, 0, 0, zone).toInstant()
        val mail = ZonedDateTime.of(2026, 3, 3, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals("3 мар.", MailFormatting.listTimestamp(mail, now, zone))
    }

    @Test
    fun listTimestampShowsFullDateForOlderYears() {
        val now = ZonedDateTime.of(2026, 11, 25, 18, 0, 0, 0, zone).toInstant()
        val mail = ZonedDateTime.of(2025, 3, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals("07.03.25", MailFormatting.listTimestamp(mail, now, zone))
    }

    @Test
    fun listTimestampIsEmptyWhenDateIsUnknown() {
        assertEquals("", MailFormatting.listTimestamp(0L, Instant.now(), zone))
    }

    @Test
    fun displayNamePrefersTheNameAndFallsBackToTheAddress() {
        assertEquals("Анна Петрова", MailFormatting.displayName("Анна Петрова <anna@company.local>"))
        assertEquals("anna@company.local", MailFormatting.displayName("<anna@company.local>"))
        assertEquals("anna@company.local", MailFormatting.displayName("anna@company.local"))
        assertEquals("", MailFormatting.displayName(null))
    }

    @Test
    fun initialIsTheFirstLetterOfTheName() {
        assertEquals("А", MailFormatting.initial("Анна Петрова <anna@company.local>"))
        assertEquals("a", MailFormatting.initial("anna@company.local"))
        assertEquals("", MailFormatting.initial("   "))
    }

    /**
     * Регрессия из первой настоящей сборки: `initial` переводила букву в верхний
     * регистр, и адрес без имени показывал «A» вместо буквы из адреса. Падал
     * `initialIsTheFirstLetterOfTheName` на строке про «anna@company.local».
     */
    @Test
    fun initialKeepsTheLetterAsWritten() {
        assertEquals("a", MailFormatting.initial("anna@company.local"))
        // «Анна» уже с заглавной — регистр строки ни при чём: функция его не трогает.
        assertEquals("А", MailFormatting.initial("Анна Петрова"))
    }

    @Test
    fun snippetCollapsesWhitespaceAndFallsBackToSubject() {
        assertEquals("Привет мир", MailFormatting.snippet("Привет\n\n   мир", "Тема"))
        assertEquals("Тема", MailFormatting.snippet("   ", "Тема"))
        assertEquals("", MailFormatting.snippet(null, null))
    }

    @Test
    fun snippetIsCutWithEllipsis() {
        val result = MailFormatting.snippet("а".repeat(200), "Тема", limit = 10)

        assertEquals(11, result.length)
        assertEquals("а".repeat(10) + "…", result)
    }

    @Test
    fun lettersCountsWithTheRightRussianEnding() {
        assertEquals("1 письмо", MailFormatting.letters(1))
        assertEquals("2 письма", MailFormatting.letters(2))
        assertEquals("5 писем", MailFormatting.letters(5))
        assertEquals("11 писем", MailFormatting.letters(11))
        assertEquals("21 письмо", MailFormatting.letters(21))
        assertEquals("22 письма", MailFormatting.letters(22))
    }
}
