package com.corvex.mobile.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имена файлов для сохранения вложений.
 *
 * Ошибка здесь не падает красиво: файл либо не создаётся, либо создаётся
 * поверх другого, либо уходит в подпапку. В письмах встречаются имена с
 * косой чертой, двоеточием («отчёт: март.pdf») и имена из точек.
 */
class AttachmentNamingTest {

    @Test
    fun pathSeparatorsAreReplaced() {
        // «../../etc/passwd» не должен увести запись за пределы папки.
        // Косые становятся подчёркиваниями, ведущие точки срезаются.
        assertEquals("_.._etc_passwd", safeFileName("../../etc/passwd"))
        assertFalse(safeFileName("../../etc/passwd").contains("/"))
    }

    @Test
    fun colonInARussianNameIsReplaced() {
        val result = safeFileName("отчёт: март.pdf")
        assertFalse("двоеточие должно исчезнуть: $result", result.contains(":"))
        assertTrue(result.startsWith("отчёт"))
        assertTrue(result.endsWith(".pdf"))
    }

    @Test
    fun leadingDotsCannotMakeAHiddenFile() {
        val result = safeFileName("...скрытый.txt")
        assertFalse("файл не должен начинаться с точки: $result", result.startsWith("."))
        assertEquals("скрытый.txt", result)
    }

    @Test
    fun emptyNameGetsAFallback() {
        assertEquals("вложение", safeFileName(""))
        assertEquals("вложение", safeFileName("   "))
        assertEquals("вложение", safeFileName("..."))
    }

    @Test
    fun veryLongNamesAreTruncated() {
        val long = "а".repeat(500) + ".pdf"
        assertEquals(120, safeFileName(long).length)
    }

    @Test
    fun normalNameIsLeftAlone() {
        assertEquals("Счёт за ноябрь.pdf", safeFileName("Счёт за ноябрь.pdf"))
        assertEquals("photo_2026.jpg", safeFileName("photo_2026.jpg"))
    }

    @Test
    fun sizeLabelIsReadable() {
        assertEquals("", com.corvex.mobile.data.imap.MailAttachment(
            name = "a", sizeBytes = 0, mimeType = "text/plain"
        ).sizeLabel())
        assertEquals("512 Б", com.corvex.mobile.data.imap.MailAttachment(
            name = "a", sizeBytes = 512, mimeType = "text/plain"
        ).sizeLabel())
        assertEquals("2 КБ", com.corvex.mobile.data.imap.MailAttachment(
            name = "a", sizeBytes = 2048, mimeType = "text/plain"
        ).sizeLabel())
        assertEquals("1.5 МБ", com.corvex.mobile.data.imap.MailAttachment(
            name = "a", sizeBytes = 1_572_864, mimeType = "image/jpeg"
        ).sizeLabel())
    }

    @Test
    fun imageTypeIsDetected() {
        assertTrue(
            com.corvex.mobile.data.imap.MailAttachment(
                name = "a.jpg", sizeBytes = 10, mimeType = "image/jpeg"
            ).isImage()
        )
        assertFalse(
            com.corvex.mobile.data.imap.MailAttachment(
                name = "a.pdf", sizeBytes = 10, mimeType = "application/pdf"
            ).isImage()
        )
    }
}
