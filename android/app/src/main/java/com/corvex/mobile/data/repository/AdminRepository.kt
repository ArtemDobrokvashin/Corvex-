package com.corvex.mobile.data.repository

import com.corvex.mobile.data.network.api.AdminAnalyticsDto
import com.corvex.mobile.data.network.api.AdminApi
import com.corvex.mobile.data.network.api.AdminGroupDto
import com.corvex.mobile.data.network.api.AdminHealthDto
import com.corvex.mobile.data.network.api.AdminUserDto
import com.corvex.mobile.data.network.api.AuditEntryDto
import com.corvex.mobile.data.network.api.PatchUserRequest
import com.corvex.mobile.data.network.api.QuotaRequest
import com.corvex.mobile.data.network.api.SetGroupsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Админка.
 *
 * Сервер отвечает 403, если у пользователя нет прав: клиент не угадывает
 * права по claim, а показывает раздел и честно сообщает «нет прав», когда
 * сервер отказал. Так права остаются в одном месте — на сервере.
 */
@Singleton
class AdminRepository @Inject constructor(
    private val adminApi: AdminApi
) {
    suspend fun users(query: String? = null, page: Int = 0): Result<UsersPage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = adminApi.users(query?.takeIf { it.isNotBlank() }, page = page)
                if (!response.isSuccessful) error(text(response.code()))
                val body = response.body() ?: error("Пустой ответ сервера")
                UsersPage(
                    users = body.content,
                    page = body.page,
                    totalPages = body.totalPages,
                    totalElements = body.totalElements
                )
            }
        }

    suspend fun groups(): Result<List<AdminGroupDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = adminApi.groups()
            if (!response.isSuccessful) error(text(response.code()))
            response.body().orEmpty()
        }
    }

    suspend fun audit(page: Int = 0): Result<List<AuditEntryDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = adminApi.audit(page = page)
            if (!response.isSuccessful) error(text(response.code()))
            response.body()?.content.orEmpty()
        }
    }

    suspend fun health(): Result<AdminHealthDto> = withContext(Dispatchers.IO) {
        runCatching {
            val response = adminApi.health()
            if (!response.isSuccessful) error(text(response.code()))
            response.body() ?: error("Пустой ответ сервера")
        }
    }

    suspend fun analytics(): Result<AdminAnalyticsDto> = withContext(Dispatchers.IO) {
        runCatching {
            val response = adminApi.analytics()
            if (!response.isSuccessful) error(text(response.code()))
            response.body() ?: error("Пустой ответ сервера")
        }
    }

    suspend fun setQuota(userId: String, quotaBytes: Long): Result<AdminUserDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = adminApi.updateQuota(userId, QuotaRequest(quotaBytes))
                if (!response.isSuccessful) error(text(response.code()))
                response.body() ?: error("Пустой ответ сервера")
            }
        }

    suspend fun setStatus(user: AdminUserDto, status: String): Result<AdminUserDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = adminApi.patchUser(user.id, PatchUserRequest(status = status))
                if (!response.isSuccessful) error(text(response.code()))
                response.body() ?: error("Пустой ответ сервера")
            }
        }

    suspend fun setGroups(userId: String, groups: List<String>): Result<AdminUserDto> =
        withContext(Dispatchers.IO) {
            runCatching {
                val response = adminApi.setGroups(userId, SetGroupsRequest(groups))
                if (!response.isSuccessful) error(text(response.code()))
                response.body() ?: error("Пустой ответ сервера")
            }
        }

    private fun text(code: Int): String = when (code) {
        403 -> "Нет прав: раздел доступен администратору"
        404 -> "Пользователь не найден"
        else -> "Сервер отклонил запрос: $code"
    }
}

data class UsersPage(
    val users: List<AdminUserDto>,
    val page: Int,
    val totalPages: Int,
    val totalElements: Long
)
