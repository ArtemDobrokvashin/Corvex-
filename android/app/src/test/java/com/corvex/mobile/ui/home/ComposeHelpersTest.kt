package com.corvex.mobile.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чистая логика экрана написания: адреса, префиксы темы, цитата.
 *
 * Проверяется юнит-тестом, потому что всё остальное в отправке — SMTP, который
 * без сети и аккаунта не проверить. Здесь ловится то, что ломается молча:
 * «Re: Re:», потерянный адрес, цитата без автора.
 */
class ComposeHelpersTest {

    @Test
    fun addressOfExtractsTheAddressFromADisplayName() {
        assertEquals("anna@company.local", addressOf("Анна Петрова <anna@company.local>"))
        assertEquals("anna@company.local", addressOf("anna@company.local"))
        assertEquals("", addressOf("Анна Петрова"))
        assertEquals("", addressOf(null))
    }

    @Test
    fun splitAddressesKeepsOnlyAddressesAndDropsDuplicates() {
        assertEquals(
            listOf("a@b.ru", "c@d.ru"),
            splitAddresses(" a@b.ru, c@d.ru ; a@b.ru ")
        )
        // Просто имя без «@» — не адрес, и в «Кому» ему не место.
        assertEquals(emptyList<String>(), splitAddresses("Анна Петрова"))
        assertEquals(listOf("a@b.ru"), splitAddresses("A@B.ru, a@b.ru"))
    }

    @Test
    fun splitAddressesTreatsSemicolonsAndSpacesAsSeparators() {
        // Гmail и mail.ru принимают и «;», и запятую; человек копирует адреса
        // из самых разных мест, и требовать один разделитель — лишний барьер.
        assertEquals(
            listOf("a@b.ru", "c@d.ru", "e@f.ru"),
            splitAddresses("a@b.ru; c@d.ru e@f.ru")
        )
    }

    @Test
    fun replyPrefixIsNotDoubled() {
        assertEquals("Re: Договор", withReplyPrefix("Договор"))
        assertEquals("Re: Договор", withReplyPrefix("Re: Договор"))
        assertEquals("Re: Договор", withReplyPrefix("re: Договор"))
    }

    @Test
    fun forwardPrefixIsNotDoubled() {
        assertEquals("Fwd: Договор", withForwardPrefix("Договор"))
        assertEquals("Fwd: Договор", withForwardPrefix("Fwd: Договор"))
        assertEquals("Fwd: Договор", withForwardPrefix("FW: Договор"))
    }

    @Test
    fun quotedTextIsIndentedAndSignedByTheAuthor() {
        val result = quoted("Первая строка\nВторая", "Анна Петрова <anna@company.local>")

        assertEquals(true, result.contains("Анна Петрова пишет:"))
        assertEquals(true, result.contains("> Первая строка"))
        assertEquals(true, result.contains("> Вторая"))
    }

    @Test
    fun quotedTextFallsBackWhenThereIsNoName() {
        val result = quoted("Текст", "")
        assertEquals(true, result.contains("Отправитель пишет:"))
    }
}
