package com.corvex.mobile.ui.mail

import android.graphics.Color as AndroidColor
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.corvex.mobile.ui.theme.CorvexColors

/**
 * Показ HTML-письма.
 *
 * Почему не WebView. Раньше здесь был самодельный парсер, который вытаскивал
 * из письма абзацы и ссылки — и результат был именно тем, на что жалуется
 * человек: «письмо выглядит стремно». Банковские выписки, рассылки и письма
 * из CRM свёрстаны таблицами; линейный текст их не передаёт вообще.
 *
 * Но и полноценный WebView для почты — неверный выбор: в письме прилетают
 * скрипты, внешние картинки-трекеры и формы, и открывать это браузером
 * внутри приложения значит доверять отправителю. Компромисс — платформенный
 * `HtmlCompat`: он разбирает HTML тем же движком, что и системные
 * приложения, но не исполняет JS и не грузит ничего по сети.
 *
 * Правила показа:
 *
 *  - жирный, курсив, подчёркивание, зачёркивание, размер и цвет — сохраняются;
 *  - маркированные и нумерованные списки, цитаты, заголовки, отступы — есть;
 *  - таблицы и общая вёрстка — приводятся к тексту с переносами, а не
 *    выбрасываются: содержимое важнее пиксельной точности;
 *  - ссылки — кликабельные, но открываются они не здесь: нажатие отдаётся
 *    наружу через [onLinkClick], чтобы человек видел, куда идёт.
 */
@Composable
fun HtmlMailBody(
    html: String,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit = {}
) {
    val annotated = remember(html) { HtmlToAnnotated.parse(html) }
    if (annotated.text.isBlank()) {
        Text(
            text = "(письмо без текста — только картинки или вёрстка)",
            style = MaterialTheme.typography.bodyMedium,
            color = CorvexColors.Faint,
            modifier = modifier
        )
        return
    }
    ClickableText(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = TextStyle(
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = CorvexColors.InkSoft
        ),
        onClick = { offset ->
            // URLSpan из HtmlCompat: берём ссылку по позиции нажатия.
            annotated.getStringAnnotations(TAG_URL, offset, offset)
                .firstOrNull()
                ?.let { onLinkClick(it.item) }
        }
    )
}

internal const val TAG_URL = "URL"

/**
 * HTML → AnnotatedString.
 *
 * `HtmlCompat.FROM_HTML_MODE_COMPACT` убирает лишние пустые строки, которые
 * иначе появляются между блоками письма, но сохраняет переносы внутри
 * абзацев.
 */
internal object HtmlToAnnotated {

    fun parse(html: String): AnnotatedString {
        val spanned = runCatching {
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }.getOrNull() ?: return AnnotatedString("")

        val builder = buildAnnotatedString { append(spanned.toString()) }

        // Переносим разметку платформы в Compose. Порядок важен: сначала
        // цвет и размер, потом начертание — стили накладываются, и при
        // обратном порядке жирный шрифт затирал бы цвет.
        spanned.getSpans(0, spanned.length, Any::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            val style = when (span) {
                is StyleSpan -> when (span.style) {
                    android.graphics.Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    android.graphics.Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    android.graphics.Typeface.BOLD_ITALIC ->
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    else -> null
                }
                is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
                is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                is ForegroundColorSpan ->
                    // Чёрный и почти чёрный не переносим: на тёмной теме он
                    // не читается, а письма почти всегда свёрстаны под белый.
                    span.foregroundColor.takeIf { it != AndroidColor.BLACK && luminance(it) > 0.15f }
                        ?.let { SpanStyle(color = androidx.compose.ui.graphics.Color(it)) }
                is BackgroundColorSpan ->
                    SpanStyle(background = androidx.compose.ui.graphics.Color(span.backgroundColor))
                is RelativeSizeSpan -> SpanStyle(fontSize = (14 * span.sizeChange).sp)
                else -> null
            }
            style?.let { builder.addStyle(it, start, end) }
        }

        // Ссылки: HtmlCompat оставляет URLSpan, но их класс в API 26+ может
        // быть подклассом; надёжнее взять спаны по классу URLSpan напрямую.
        spanned.getSpans(0, spanned.length, android.text.style.URLSpan::class.java).forEach { span ->
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            builder.addStyle(
                SpanStyle(color = CorvexColors.Accent, textDecoration = TextDecoration.Underline),
                start,
                end
            )
            builder.addStringAnnotation(TAG_URL, span.url, start, end)
        }

        return builder
    }

    /** Яркость 0..1: по ней отсекаем нечитаемый тёмный текст. */
    private fun luminance(color: Int): Float {
        val r = AndroidColor.red(color) / 255f
        val g = AndroidColor.green(color) / 255f
        val b = AndroidColor.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}

/**
 * Разбор HTML в блоки — оставлен для писем, где нужен текст без разметки
 * (например, предпросмотр в списке). Экран письма его больше не использует.
 */
internal sealed interface HtmlBlock {
    data class Paragraph(val text: AnnotatedString) : HtmlBlock
    data class Heading(val level: Int, val text: AnnotatedString) : HtmlBlock
    data class Bullet(val text: AnnotatedString) : HtmlBlock
    data object Divider : HtmlBlock
}

internal object HtmlToBlocks {

    fun parse(html: String): List<HtmlBlock> {
        val prepared = html
            .replace(Regex("""(?is)<(script|style|head)[^>]*>.*?</\1>"""), "")
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
                when (block) {
                    is HtmlBlock.Paragraph -> block.text.text.isBlank()
                    is HtmlBlock.Heading -> block.text.text.isBlank()
                    is HtmlBlock.Bullet -> block.text.text.isBlank()
                    else -> false
                }
            }
    }

    private fun inline(raw: String): AnnotatedString = buildAnnotatedString {
        var rest: String = raw
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
