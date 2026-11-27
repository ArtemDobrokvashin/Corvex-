package com.corvex.mobile.ui.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Квота в админке: гигабайты на экране, байты на сервере.
 *
 * Ошибка на порядок (ГБ против ГиБ, запятая против точки) — это либо отказ
 * сервера, либо квота в 1024 раза меньше ожидаемой. Поэтому разбор и обратное
 * форматирование проверяются отдельно.
 */
class AdminQuotaTest {

    private val gib = 1024.0 * 1024 * 1024

    @Test
    fun `comma and dot both work`() {
        assertEquals((2.5 * gib).toLong(), quotaGigabytes("2,5"))
        assertEquals((2.5 * gib).toLong(), quotaGigabytes("2.5"))
    }

    @Test
    fun `whole gigabytes`() {
        assertEquals((10.0 * gib).toLong(), quotaGigabytes("10"))
        assertEquals(0L, quotaGigabytes("0"))
    }

    @Test
    fun `invalid input is rejected instead of guessed`() {
        assertNull(quotaGigabytes(""))
        assertNull(quotaGigabytes("много"))
        assertNull(quotaGigabytes("-1"))
    }

    @Test
    fun `bytes are shown back as gigabytes`() {
        assertEquals("2.5", gigabytesNumber((2.5 * gib).toLong()))
        assertEquals("10", gigabytesNumber((10.0 * gib).toLong()))
        assertEquals("0", gigabytesNumber(0))
    }

    @Test
    fun `round trip keeps the value`() {
        listOf("1", "2.5", "10", "0.5").forEach { input ->
            val bytes = quotaGigabytes(input)!!
            assertEquals(input, gigabytesNumber(bytes))
        }
    }

    @Test
    fun `human size uses gigabytes for zero`() {
        assertEquals("0 ГБ", gigabytes(0))
    }
}
