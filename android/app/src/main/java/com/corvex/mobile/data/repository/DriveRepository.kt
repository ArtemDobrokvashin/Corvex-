package com.corvex.mobile.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.corvex.mobile.data.local.AttachmentStore
import com.corvex.mobile.data.parseInstant
import com.corvex.mobile.data.network.api.CreateFolderRequest
import com.corvex.mobile.data.network.api.CreateShareRequest
import com.corvex.mobile.data.network.api.DriveApi
import com.corvex.mobile.data.network.api.DriveNodeDto
import com.corvex.mobile.data.network.api.DriveShareCreatedDto
import com.corvex.mobile.data.network.api.MoveNodeRequest
import com.corvex.mobile.data.network.api.RenameNodeRequest
import com.corvex.mobile.domain.drive.DriveNode
import com.corvex.mobile.domain.drive.DriveShare
import com.corvex.mobile.domain.drive.DriveUsage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Диск.
 *
 * Скачивание пишет поток сразу на диск через [AttachmentStore]: держать файл
 * в памяти телефона нельзя — на большом PDF приложение убьёт система.
 */
@Singleton
class DriveRepository @Inject constructor(
    private val driveApi: DriveApi,
    private val attachmentStore: AttachmentStore,
    @ApplicationContext private val context: Context
) {
    suspend fun list(parentId: String?, view: String = "files", query: String? = null): Result<List<DriveNode>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = driveApi.list(parentId, view, query?.takeIf { it.isNotBlank() })
                if (!response.isSuccessful) error("Не удалось открыть папку: ${response.code()}")
                response.body().orEmpty().map { it.toDomain() }
            }
        }

    suspend fun folders(): Result<List<DriveNode>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = driveApi.folders()
            if (!response.isSuccessful) error("Не удалось получить папки: ${response.code()}")
            response.body().orEmpty().map { it.toDomain() }
        }
    }

    suspend fun usage(): Result<DriveUsage> = withContext(Dispatchers.IO) {
        runCatching {
            val response = driveApi.usage()
            if (!response.isSuccessful) error("Не удалось узнать квоту: ${response.code()}")
            val body = response.body() ?: error("Пустой ответ сервера")
            DriveUsage(body.usedBytes, body.quotaBytes)
        }
    }

    suspend fun createFolder(parentId: String?, name: String): Result<DriveNode> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = driveApi.createFolder(CreateFolderRequest(parentId, name.trim()))
                if (!response.isSuccessful) error(errorText(response.code(), "папку"))
                response.body()?.toDomain() ?: error("Пустой ответ сервера")
            }
        }

    suspend fun upload(parentId: String?, uri: Uri): Result<DriveNode> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val name = displayName(uri)
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            // Поток из `content://` отдаётся в OkHttp лениво: тело читается
            // уже во время отправки, а дескриптор открывается там же и там же
            // закрывается. Читать файл в `ByteArray` нельзя — видео на 300 МБ
            // уронит приложение по OOM ещё до начала отправки.
            val body = object : RequestBody() {
                override fun contentType() = mime.toMediaTypeOrNull()

                // Длина известна заранее: без неё OkHttp шлёт chunked, и
                // сервер не может отклонить слишком большой файл до чтения.
                override fun contentLength(): Long = sizeOf(uri)

                /**
                 * OkHttp может вызвать `writeTo` повторно (редирект, повторная
                 * попытка), поэтому поток открывается здесь, а не снаружи:
                 * закрытый дескриптор на втором вызове дал бы пустое тело.
                 */
                override fun writeTo(sink: BufferedSink) {
                    val input = resolver.openInputStream(uri)
                        ?: error("Не удалось прочитать файл")
                    input.use { stream -> sink.writeAll(stream.source()) }
                }
            }
            val part = MultipartBody.Part.createFormData("file", name, body)
            val response = driveApi.upload(parentId, part)
            if (!response.isSuccessful) error(errorText(response.code(), "файл"))
            response.body()?.toDomain() ?: error("Пустой ответ сервера")
        }
    }

    suspend fun rename(id: String, name: String): Result<DriveNode> = withContext(Dispatchers.IO) {
        runCatching {
            val response = driveApi.rename(id, RenameNodeRequest(name.trim()))
            if (!response.isSuccessful) error("Не удалось переименовать: ${response.code()}")
            response.body()?.toDomain() ?: error("Пустой ответ сервера")
        }
    }

    suspend fun move(id: String, parentId: String?): Result<DriveNode> = withContext(Dispatchers.IO) {
        runCatching {
            val response = driveApi.move(id, MoveNodeRequest(parentId))
            if (!response.isSuccessful) error("Не удалось переместить: ${response.code()}")
            response.body()?.toDomain() ?: error("Пустой ответ сервера")
        }
    }

    suspend fun trash(id: String): Result<Unit> = simple { driveApi.trash(id) }
    suspend fun restore(id: String): Result<Unit> = simple { driveApi.restore(id) }
    suspend fun purge(id: String): Result<Unit> = simple { driveApi.purge(id) }

    /**
     * Скачивание в «Загрузки».
     *
     * Сеть → MediaStore напрямую, без промежуточного `ByteArray`: файл на
     * диске бывает в сотни мегабайт, и чтение его в память — это OOM на
     * телефоне с 4 ГБ. `ResponseBody` закрывается `use`.
     */
    suspend fun download(node: DriveNode): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = driveApi.download(node.id)
            if (!response.isSuccessful) error("Не удалось скачать файл: ${response.code()}")
            val body = response.body() ?: error("Пустой ответ сервера")
            body.use { source ->
                attachmentStore.saveStream(
                    name = node.name,
                    mimeType = node.contentType ?: "application/octet-stream"
                ) { output ->
                    source.byteStream().use { input -> input.copyTo(output) }
                }.getOrThrow()
            }
        }
    }

    suspend fun shares(nodeId: String): Result<List<DriveShare>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = driveApi.shares(nodeId)
            if (!response.isSuccessful) error("Не удалось получить ссылки: ${response.code()}")
            response.body().orEmpty().map {
                DriveShare(
                    id = it.id,
                    nodeId = it.nodeId,
                    expiresAt = parseInstant(it.expiresAt),
                    revoked = !it.revokedAt.isNullOrBlank()
                )
            }
        }
    }

    suspend fun createShare(nodeId: String, expiresInDays: Int?): Result<DriveShareCreatedDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = driveApi.createShare(nodeId, CreateShareRequest(expiresInDays))
                if (!response.isSuccessful) error("Не удалось создать ссылку: ${response.code()}")
                response.body() ?: error("Пустой ответ сервера")
            }
        }

    suspend fun revokeShare(shareId: String): Result<Unit> = simple { driveApi.revokeShare(shareId) }

    private suspend fun simple(call: suspend () -> retrofit2.Response<Unit>): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = call()
                if (!response.isSuccessful) error("Сервер отклонил действие: ${response.code()}")
            }
        }

    /** Размер файла из провайдера: `-1` означает «неизвестен», тогда chunked. */
    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
            ?: -1L
    }.getOrDefault(-1L)

    private fun displayName(uri: Uri): String {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file"
    }

    private fun errorText(code: Int, what: String): String =
        if (code == 413) "Файл больше свободного места на диске"
        else "Не удалось загрузить $what: $code"
}

internal fun DriveNodeDto.toDomain(): DriveNode = DriveNode(
    id = id,
    parentId = parentId,
    name = name,
    isFolder = kind.equals("FOLDER", ignoreCase = true),
    sizeBytes = sizeBytes,
    contentType = contentType,
    trashed = !trashedAt.isNullOrBlank(),
    updatedAt = parseInstant(updatedAt ?: createdAt)
)
