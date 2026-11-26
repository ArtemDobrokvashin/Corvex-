package com.corvex.mobile.ui.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор HTML письма.
 *
 * Письма от банков и рассылок — это HTML с таблицами, ссылками и трекерами.
 * Показывать их выжимкой без разметки нельзя: получалась каша из адресов и
 * пустых строк — именно это было видно на экране. Тесты фиксируют то, что
 * должно получиться: абзацы, текст ссылок без адресов, отсутствие скриптов и
 * пустых дыр.
 */
class HtmlToBlocksTest {

    /** Текст блока независимо от его вида: тесту важна читаемость. */
    private fun HtmlBlock.text(): String = when (this) {
        is HtmlBlock.Paragraph -> text.text
        is HtmlBlock.Heading -> text.text
        is HtmlBlock.Bullet -> text.text
        HtmlBlock.Divider -> ""
    }

    @Test
    fun paragraphsBecomeSeparateBlocks() {
        val blocks = HtmlToBlocks.parse("<p>Первый</p><p>Второй</p>")

        assertEquals(2, blocks.size)
        assertEquals("Первый", blocks[0].text())
        assertEquals("Второй", blocks[1].text())
    }

    @Test
    fun scriptAndStyleAreDroppedEntirely() {
        val html = """
            <html><head><style>.a{color:red}</style></head>
            <body><script>alert('x')</script><p>Текст письма</p></body></html>
        """.trimIndent()

        val all = HtmlToBlocks.parse(html).joinToString(" ") { it.text() }

        assertFalse("стили не должны попадать в текст: $all", all.contains("color:red"))
        assertFalse("скрипты не должны попадать в текст: $all", all.contains("alert"))
        assertTrue(all.contains("Текст письма"))
    }

    @Test
    fun linkKeepsItsTextAndLosesTheMarkup() {
        val blocks = HtmlToBlocks.parse(
            """<p>Перейдите <a href="https://lk.tinkoff.ru/x">по ссылке</a> в кабинет</p>"""
        )

        val text = blocks.single().text()
        assertTrue("текст ссылки должен остаться: $text", text.contains("по ссылке"))
        assertFalse("тег не должен остаться в тексте", text.contains("<a"))
        assertFalse("адрес не должен лезть в текст письма", text.contains("https://lk.tinkoff.ru"))
    }

    @Test
    fun lineBreaksSeparateLinesInsteadOfGlueingThem() {
        val blocks = HtmlToBlocks.parse("Отправитель<br>Сумма: 100<br>Дата: сегодня")

        assertEquals(1, blocks.size)
        val text = blocks.single().text()
        assertTrue(
            "все строки должны остаться: $text",
            text.contains("Отправитель") && text.contains("Сумма: 100") && text.contains("Дата: сегодня")
        )
    }

    @Test
    fun emptyMarkupDoesNotProduceEmptyBlocks() {
        // Такая разметка — обычное дело: дивы-обёртки без текста.
        val blocks = HtmlToBlocks.parse("<div><div></div><p> </p><span></span></div><p>Содержание</p>")

        assertEquals(1, blocks.size)
        assertEquals("Содержание", blocks.single().text())
    }

    @Test
    fun entitiesAreDecoded() {
        val blocks = HtmlToBlocks.parse("<p>Счёт&nbsp;оплачен &amp; закрыт &laquo;вчера&raquo;</p>")

        assertEquals("Счёт оплачен & закрыт «вчера»", blocks.single().text())
    }

    @Test
    fun boldIsRemovedFromTheTextButTheWordsStay() {
        val blocks = HtmlToBlocks.parse("<p>Важно: <b>остался 1 день</b></p>")

        val text = blocks.single().text()
        assertTrue(text.contains("Важно:"))
        assertTrue(text.contains("остался 1 день"))
        assertFalse(text.contains("<b>"))
    }

    @Test
    fun horizontalRuleBecomesADividerNotGarbage() {
        val blocks = HtmlToBlocks.parse("<p>Выше</p><hr><p>Ниже</p>")

        assertTrue(blocks.any { it is HtmlBlock.Divider })
        assertTrue(blocks.joinToString(" ") { it.text() }.contains("Ниже"))
    }
}
