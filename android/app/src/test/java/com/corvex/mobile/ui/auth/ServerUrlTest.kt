package com.corvex.mobile.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Адрес сервера на первом экране.
 *
 * Первая установка на эмулятор показала дефект: поле было предзаполнено
 * «https://», человек ввёл «10.0.2.2:8080» — запрос ушёл строкой без схемы и
 * упал с «Ошибка сети». Тест держит оба свойства:
 *   1. адрес без схемы получает схему, и http — именно для локальных адресов;
 *   2. явно указанная схема не переписывается.
 */
class ServerUrlTest {

    @Test
    fun addsHttpsToABareHost() {
        assertEquals("https://mail.company.ru", normalizedServerUrl("mail.company.ru"))
        assertEquals("https://mail.company.ru", normalizedServerUrl("  mail.company.ru  "))
    }

    @Test
    fun keepsAnExplicitScheme() {
        assertEquals("https://mail.company.ru", normalizedServerUrl("https://mail.company.ru"))
        assertEquals("http://10.0.2.2:8080", normalizedServerUrl("http://10.0.2.2:8080"))
    }

    /**
     * Эмулятор ходит на хост-машину по `10.0.2.2`, и TLS там обычно нет:
     * `network_security_config.xml` разрешает открытый HTTP именно для него.
     */
    @Test
    fun usesHttpForLocalAddresses() {
        assertEquals("http://10.0.2.2:8080", normalizedServerUrl("10.0.2.2:8080"))
        assertEquals("http://localhost:8080", normalizedServerUrl("localhost:8080"))
    }

    @Test
    fun dropsATrailingSlashAndRejectsEmptyInput() {
        assertEquals("https://mail.company.ru", normalizedServerUrl("mail.company.ru/"))
        assertEquals("", normalizedServerUrl(""))
        assertEquals("", normalizedServerUrl("   "))
        // Строка из одной схемы — это не адрес, и в сеть с ней идти нельзя.
        assertEquals("", normalizedServerUrl("/"))
    }

    @Test
    fun keepsThePathLikePartThatFollowsTheHost() {
        assertEquals("https://mail.company.ru/corvex", normalizedServerUrl("mail.company.ru/corvex"))
    }
}
