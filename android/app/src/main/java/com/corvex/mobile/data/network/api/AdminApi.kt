package com.corvex.mobile.data.network.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * admin-service (gateway: /api/v1/admin).
 *
 * Права проверяет сервер по claim `permissions`: в токене нет и не должно быть
 * `SCOPE_admin`, поэтому клиент не решает, «админ ли я» — он показывает раздел,
 * а 403 от сервера означает «нет прав».
 */
@Serializable
data class PageResultDto<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0
)

@Serializable
data class AdminUserDto(
    val id: String,
    val username: String = "",
    val email: String = "",
    val displayName: String = "",
    val source: String = "",
    val status: String = "ACTIVE",
    val department: String? = null,
    val groups: List<String> = emptyList(),
    val quotaBytes: Long = 0,
    val usedBytes: Long? = null,
    val mustChangePassword: Boolean = false,
    val totpEnabled: Boolean = false,
    val lastLoginAt: String? = null,
    val createdAt: String? = null
)

@Serializable
data class AdminGroupDto(
    val code: String,
    val name: String = "",
    val isSystem: Boolean = false,
    val enabled: Boolean = true,
    val permissions: List<String> = emptyList(),
    val memberCount: Long = 0
)

@Serializable
data class AuditEntryDto(
    val id: Long,
    val actorId: String? = null,
    val actorName: String? = null,
    val action: String = "",
    val entity: String? = null,
    val entityId: String? = null,
    // `details` — Map<String, Object>; в JSON это объект, а не плоская строка.
    // Без своего типа десериализация упала бы на каждой записи аудита.
    val details: JsonObject? = null,
    val ipAddress: String? = null,
    val createdAt: String? = null
)

@Serializable
data class ServiceHealthDto(
    val name: String,
    val status: String,
    val version: String? = null
)

@Serializable
data class AdminHealthDto(
    val services: List<ServiceHealthDto> = emptyList(),
    val diskFreeBytes: Long = 0,
    val mailQueueDepth: Long = 0
)

@Serializable
data class AdminAnalyticsDto(
    val scope: String = "",
    val department: String? = null,
    val usersTotal: Long = 0,
    val usersActive: Long = 0,
    val usersBlocked: Long = 0,
    val usersDisabled: Long = 0,
    val loginsLast24h: Long? = null,
    val loginsLast7d: Long? = null,
    val generatedAt: String? = null
)

@Serializable
data class QuotaRequest(val quotaBytes: Long)

@Serializable
data class PatchUserRequest(
    val displayName: String? = null,
    val email: String? = null,
    val department: String? = null,
    val status: String? = null
)

@Serializable
data class SetGroupsRequest(val groupCodes: List<String>)

interface AdminApi {
    @GET("admin/users")
    suspend fun users(
        @Query("q") query: String? = null,
        @Query("status") status: String? = null,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<PageResultDto<AdminUserDto>>

    @PATCH("admin/users/{userId}")
    suspend fun patchUser(
        @Path("userId") userId: String,
        @Body request: PatchUserRequest
    ): Response<AdminUserDto>

    @PATCH("admin/users/{userId}/quota")
    suspend fun updateQuota(
        @Path("userId") userId: String,
        @Body request: QuotaRequest
    ): Response<AdminUserDto>

    @POST("admin/users/{userId}/block")
    suspend fun blockUser(@Path("userId") userId: String): Response<Unit>

    @DELETE("admin/users/{userId}")
    suspend fun disableUser(@Path("userId") userId: String): Response<Unit>

    @PUT("admin/users/{userId}/groups")
    suspend fun setGroups(
        @Path("userId") userId: String,
        @Body request: SetGroupsRequest
    ): Response<AdminUserDto>

    @GET("admin/groups")
    suspend fun groups(): Response<List<AdminGroupDto>>

    @GET("admin/audit")
    suspend fun audit(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): Response<PageResultDto<AuditEntryDto>>

    @GET("admin/system/health")
    suspend fun health(): Response<AdminHealthDto>

    @GET("admin/analytics/activity")
    suspend fun analytics(): Response<AdminAnalyticsDto>
}
