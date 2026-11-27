package com.corvex.mobile.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Размер файла и квота — единственная «математика» на экране файлов.
 *
 * Ошибка здесь не падает, а врёт: «1,0 МБ» вместо «12,4 МБ» человек читает
 * как факт и делает вывод о диске.
 */
class FilesFormattingTest {

    /** Разделитель дроби зависит от локали JVM, поэтому сверяем цифры и единицу. */
    private fun assertSize(expected: String, bytes: Long) {
        val actual = bytesOf(bytes).replace(',', '.').replace('\u00A0', ' ')
        assertEquals(expected, actual)
    }

    @Test
    fun `bytes below a kilobyte are shown as is`() {
        assertEquals("0 Б", bytesOf(0))
        assertEquals("512 Б", bytesOf(512))
        assertEquals("1023 Б", bytesOf(1023))
    }

    @Test
    fun `units step by 1024`() {
        assertSize("1.0 КБ", 1024)
        assertSize("1.0 МБ", 1024L * 1024)
        assertSize("1.0 ГБ", 1024L * 1024 * 1024)
    }

    @Test
    fun `rounding keeps one decimal`() {
        assertSize("1.5 МБ", (1.5 * 1024 * 1024).toLong())
        assertSize("12.4 МБ", (12.4 * 1024 * 1024).toLong())
    }

    @Test
    fun `negative size does not produce nonsense`() {
        // Сервер не пришлёт отрицательный размер, но у папки sizeBytes = 0,
        // и на всякий случай отрицательное не должно стать «-1,0 ГБ».
        val actual = bytesOf(-5).replace(',', '.')
        assertEquals("0 Б", actual)
    }
}
