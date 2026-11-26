package com.corvex.mobile.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corvex.mobile.ui.theme.CorvexColors

/**
 * Показ HTML-письма.
 *
 * Почему не WebView: WebView — это движок, к которому нужен интернет, JS и
 * политика безопасности; в письме от банка прилетает разметка, картинки и
 * трекеры, и открывать это в полноценном браузере внутри приложения — риск,
 * который для почты не оправдан. Здесь разметка разбирается в ограниченный
 * набор того, что реально встречается в письмах: абзацы, строки, ссылки,
 * жирный/курсив, заголовки, списки, цитаты, горизонтальные линии.
 *
 * Блочная вёрстка (`<table>`, `<div>` с float) намеренно не повторяется:
 * попадание в неё — лотерея, а аккуратный линейный текст читается лучше, чем
 * кривая сетка.
 */
@Composable
fun HtmlMailBody(html: String, modifier: Modifier = Modifier) {
    val blocks = remember(html) { HtmlToBlocks.parse(html) }
    if (blocks.isEmpty()) {
        Text(
            text = "(письмо без текста — только картинки или вёрстка)",
            style = MaterialTheme.typography.bodyMedium,
            color = CorvexColors.Faint,
            modifier = modifier
        )
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block -> HtmlBlockView(block) }
    }
}

@Composable
private fun HtmlBlockView(block: HtmlBlock) {
    when (block) {
        is HtmlBlock.Paragraph -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = CorvexColors.InkSoft,
            lineHeight = 22.sp
        )
        is HtmlBlock.Heading -> Text(
            text = block.text,
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            fontWeight = FontWeight.SemiBold,
            color = CorvexColors.Ink
        )
        HtmlBlock.Divider -> Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CorvexColors.Line)
        )
        is HtmlBlock.Bullet -> Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "•",
                style = MaterialTheme.typography.bodyMedium,
                color = CorvexColors.Faint
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = CorvexColors.InkSoft
            )
        }
    }
}

internal sealed interface HtmlBlock {
    data class Paragraph(val text: AnnotatedString) : HtmlBlock
    data class Heading(val level: Int, val text: AnnotatedString) : HtmlBlock
    data class Bullet(val text: AnnotatedString) : HtmlBlock
    data object Divider : HtmlBlock
}

/**
 * Разбор письма в блоки.
 *
 * Это не парсер HTML и не пытается им быть: он понимает теги, которые
 * действительно приходят в письмах, и не падает на остальном. Всё, что не
 * распознано, становится текстом — потерять содержимое хуже, чем потерять
 * оформление.
 */
internal object HtmlToBlocks {

    fun parse(html: String): List<HtmlBlock> {
        val prepared = html
            // Служебное выкидываем целиком: скрипты и стили текстом не являются.
            .replace(Regex("""(?is)<(script|style|head)[^>]*>.*?</\1>"""), "")
            // Блочные границы → перевод строки, чтобы абзацы не склеивались.
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)<(h[1-6])[^>]*>"""), "\n\n<h$1>")
            .replace(Regex("""(?i)</(p|div|tr|li|h[1-6]|blockquote|table)>"""), "\n\n")
            .replace(Regex("""(?i)<hr\s*/?>"""), "\n\n---\n\n")
            .replace(Regex("""(?i)<li[^>]*>"""), "\n\n• ")

        return prepared
            .split(Regex("""\n{2,}"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { raw ->
                when {
                    raw == "---" -> HtmlBlock.Divider
                    else -> {
                        val heading = Regex("""^\s*<h([1-6])[^>]*>(.*?)</h\1>""", RegexOption.IGNORE_CASE)
                            .find(raw)
                        when {
                            heading != null -> HtmlBlock.Heading(
                                level = heading.groupValues[1].toIntOrNull() ?: 3,
                                text = inline(heading.groupValues[2])
                            )
                            raw.startsWith("•") -> HtmlBlock.Bullet(inline(raw.removePrefix("•")))
                            else -> HtmlBlock.Paragraph(inline(raw))
                        }
                    }
                }
            }
            .filterNot { block ->
                // Пустые блоки после снятия тегов не рисуем: иначе в письме
                // появляются дыры высотой в абзац.
                when (block) {
                    is HtmlBlock.Paragraph -> block.text.text.isBlank()
                    is HtmlBlock.Heading -> block.text.text.isBlank()
                    is HtmlBlock.Bullet -> block.text.text.isBlank()
                    else -> false
                }
            }
    }

    /**
     * Разметка внутри строки: ссылки, жирный, курсив.
     *
     * Ссылка рисуется подчёркнутой и цветом акцента, но текстом, а не
     * кликабельной — переход наружу из письма заслуживает отдельного
     * подтверждения, а не случайного нажатия в тексте.
     */
    private fun inline(raw: String): AnnotatedString = buildAnnotatedString {
        var rest: String = raw

        // Ссылки: [текст](url) → текст, с адресом в подсказке не возимся —
        // показываем текст ссылки, а адрес дописываем, если текст пуст.
        // Заголовок внутри абзаца (когда тег «прилип» к соседнему тексту):
        // вынимаем текст, чтобы он не пропал.
        rest = rest.replace(Regex("""(?is)</?h[1-6][^>]*>"""), " ")
        val link = Regex("""(?is)<a\s[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""")
        var index = 0
        link.findAll(rest).forEach { match ->
            append(inlinePlain(rest.substring(index, match.range.first)))
            val href = match.groupValues[1]
            val label = inlinePlain(match.groupValues[2])
            withStyle(SpanStyle(color = CorvexColors.Accent, textDecoration = TextDecoration.Underline)) {
                if (label.text.isBlank()) append(href) else append(label)
            }
            index = match.range.last + 1
        }
        if (index == 0) {
            append(inlinePlain(rest))
        } else {
            append(inlinePlain(rest.substring(index)))
        }
    }

    /** Оставшаяся разметка: жирный, курсив, зачёркивание, сущности. */
    private fun inlinePlain(raw: String): AnnotatedString = buildAnnotatedString {
        var text = raw
            .replace(Regex("""(?is)<(strong|b)[^>]*>(.*?)</\1>"""), "\u0001$2\u0002")
            .replace(Regex("""(?is)<(em|i)[^>]*>(.*?)</\1>"""), "\u0003$2\u0004")
        text = unescape(stripTags(text))

        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\u0001' -> {
                    val end = text.indexOf('\u0002', i)
                    if (end < 0) { append(text.substring(i + 1)); i = text.length }
                    else {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    }
                }
                '\u0003' -> {
                    val end = text.indexOf('\u0004', i)
                    if (end < 0) { append(text.substring(i + 1)); i = text.length }
                    else {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    }
                }
                else -> {
                    val next = text.indexOfMarker(i) { it == '\u0001' || it == '\u0003' }
                    if (next < 0) { append(text.substring(i)); i = text.length }
                    else { append(text.substring(i, next)); i = next }
                }
            }
        }
    }

    private fun CharSequence.indexOfMarker(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }

    private fun stripTags(value: String): String =
        value.replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()

    private fun unescape(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&laquo;", "«")
        .replace("&raquo;", "»")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
}
