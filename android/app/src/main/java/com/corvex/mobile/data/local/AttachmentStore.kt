package com.corvex.mobile.data.local

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сохранение вложений на устройство.
 *
 * Два пути, потому что так устроен Android:
 *
 *  - **API 29+** — через `MediaStore` в общую папку `Download`. Разрешений не
 *    нужно, файл виден в «Файлах» и доступен другим приложениям. Это то, чего
 *    человек ждёт от «скачать вложение».
 *  - **API 26–28** — `MediaStore.Downloads` ещё не было. Пишем в личную папку
 *    приложения: разрешение `WRITE_EXTERNAL_STORAGE` ради одного файла —
 *    лишнее, а папка приложения не требует ничего. Файл виден по пути
 *    `Android/data/com.corvex.mobile/files/Download`.
 *
 * Имя файла чистится: в письмах встречаются `отчёт: за март.pdf` и имена с
 * косой чертой, которые превратились бы в путь.
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun save(name: String, mimeType: String, bytes: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val safeName = sanitize(name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeToDownloads(safeName, mimeType, bytes)
                } else {
                    writeToAppFolder(safeName, bytes)
                }
            }
        }

    /**
     * Сохранение из потока: файл может быть больше, чем помещается в память.
     *
     * @param bytesToWrite принимает выходной поток и пишет в него данные —
     *        так `AttachmentStore` остаётся единственным местом, которое знает
     *        про MediaStore, а источник данных (сеть, диск) — снаружи.
     */
    suspend fun saveStream(
        name: String,
        mimeType: String,
        bytesToWrite: (OutputStream) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = sanitize(name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeToDownloads(safeName, mimeType, bytesToWrite)
            } else {
                writeToAppFolder(safeName, bytesToWrite)
            }
        }
    }

    private fun writeToDownloads(name: String, mimeType: String, bytes: ByteArray): String =
        writeToDownloads(name, mimeType, { it.write(bytes) })

    private fun writeToDownloads(
        name: String,
        mimeType: String,
        write: (OutputStream) -> Unit
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Не удалось создать файл в «Загрузках»")
        try {
            resolver.openOutputStream(uri)?.use { write(it) }
                ?: error("Не удалось открыть файл для записи")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            // Недописанный файл в «Загрузках» — мусор, который человек увидит
            // и не поймёт, откуда он. Убираем.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return name
    }

    private fun writeToAppFolder(name: String, bytes: ByteArray): String =
        writeToAppFolder(name, { it.write(bytes) })

    private fun writeToAppFolder(name: String, write: (OutputStream) -> Unit): String {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use(write)
        return file.absolutePath
    }

    /**
     * Имя, которое можно записать на диск: без разделителей пути, двоеточий
     * и управляющих символов. Пустое имя заменяется на «вложение».
     */
    internal fun sanitize(name: String): String = safeFileName(name)
}


/**
 * Имя файла, которое можно записать на диск.
 *
 * Вынесено из класса, чтобы проверялось юнит-тестом без Android: само
 * сохранение требует `Context`, а правило имени — чистая функция, и
 * ошибиться в нём легко (пути, двоеточия, имена из одних точек).
 */
internal fun safeFileName(name: String): String {
    val cleaned = name
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
        .replace(Regex("""[\u0000-\u001f]"""), "")
        .trim()
        .trimStart('.')
    return cleaned.ifBlank { "вложение" }.take(120)
}
