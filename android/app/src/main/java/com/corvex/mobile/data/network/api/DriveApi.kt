package com.corvex.mobile.data.network.api

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Личный диск (gateway: /api/v1/drive).
 *
 * Формы сверены с `DriveController` и `DriveDtos`: `kind` — строка `FILE` /
 * `FOLDER`, `trashedAt` приходит как null у живых объектов.
 */
@Serializable
data class DriveNodeDto(
    val id: String,
    val parentId: String? = null,
    val kind: String = "FILE",
    val name: String = "",
    val sizeBytes: Long = 0,
    val contentType: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val trashedAt: String? = null
)

@Serializable
data class DriveUsageDto(
    val usedBytes: Long = 0,
    val quotaBytes: Long = 0
)

@Serializable
data class DriveShareDto(
    val id: String,
    val nodeId: String,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val revokedAt: String? = null
)

/** `token` приходит один раз: в хранилище сервера лежит только его SHA-256. */
@Serializable
data class DriveShareCreatedDto(
    val share: DriveShareDto,
    val token: String,
    val path: String
)

@Serializable
data class CreateFolderRequest(val parentId: String? = null, val name: String)

@Serializable
data class RenameNodeRequest(val name: String)

@Serializable
data class MoveNodeRequest(val parentId: String? = null)

@Serializable
data class CreateShareRequest(val expiresInDays: Int? = null)

interface DriveApi {
    /**
     * Содержимое папки.
     *
     * `@GET("drive")` без завершающей косой: BaseUrlInterceptor только меняет
     * хост, путь сохраняется. Сервер принимает и `/api/v1/drive`, и с косой.
     */
    @GET("drive")
    suspend fun list(
        @Query("parentId") parentId: String? = null,
        @Query("view") view: String = "files",
        @Query("q") query: String? = null
    ): Response<List<DriveNodeDto>>

    @GET("drive/folders")
    suspend fun folders(): Response<List<DriveNodeDto>>

    @GET("drive/usage")
    suspend fun usage(): Response<DriveUsageDto>

    @POST("drive/folders")
    suspend fun createFolder(@Body request: CreateFolderRequest): Response<DriveNodeDto>

    @Multipart
    @POST("drive/files")
    suspend fun upload(
        @Query("parentId") parentId: String?,
        @Part file: MultipartBody.Part
    ): Response<DriveNodeDto>

    @PATCH("drive/{id}/name")
    suspend fun rename(@Path("id") id: String, @Body request: RenameNodeRequest): Response<DriveNodeDto>

    @POST("drive/{id}/move")
    suspend fun move(@Path("id") id: String, @Body request: MoveNodeRequest): Response<DriveNodeDto>

    @POST("drive/{id}/trash")
    suspend fun trash(@Path("id") id: String): Response<Unit>

    @POST("drive/{id}/restore")
    suspend fun restore(@Path("id") id: String): Response<Unit>

    @DELETE("drive/{id}")
    suspend fun purge(@Path("id") id: String): Response<Unit>

    /**
     * Скачивание.
     *
     * `@Streaming` обязателен: без него Retrofit/OkHttp сначала прочитает всё
     * тело в память, и файл на 500 МБ уронит приложение по OOM. Поток
     * закрывает вызывающий код.
     */
    @Streaming
    @GET("drive/{id}/content")
    suspend fun download(@Path("id") id: String): Response<ResponseBody>

    @GET("drive/{id}/shares")
    suspend fun shares(@Path("id") id: String): Response<List<DriveShareDto>>

    @POST("drive/{id}/shares")
    suspend fun createShare(
        @Path("id") id: String,
        @Body request: CreateShareRequest
    ): Response<DriveShareCreatedDto>

    @DELETE("drive/shares/{shareId}")
    suspend fun revokeShare(@Path("shareId") shareId: String): Response<Unit>
}
