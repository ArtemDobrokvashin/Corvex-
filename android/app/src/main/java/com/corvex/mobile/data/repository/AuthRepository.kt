package com.corvex.mobile.data.repository

import com.corvex.mobile.data.network.api.AuthApi
import com.corvex.mobile.data.network.api.LoginRequest
import com.corvex.mobile.data.network.api.MfaVerifyRequest
import com.corvex.mobile.data.network.api.ProblemDetails
import com.corvex.mobile.data.security.TokenStore
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    data object Authenticated : AuthResult()
    data class MfaRequired(val mfaTicket: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val json: Json
) {
    suspend fun login(serverUrl: String, username: String, password: String): AuthResult {
        val cleanUrl = serverUrl.trim()
        val previousUrl = tokenStore.serverUrl
        tokenStore.serverUrl = cleanUrl
        return try {
            val response = authApi.login(LoginRequest(username.trim(), password))
            if (response.isSuccessful || response.code() == 202) {
                val body = response.body() ?: run {
                    tokenStore.serverUrl = previousUrl
                    return AuthResult.Error("Пустой ответ сервера")
                }
                if (body.mfaRequired && !body.mfaTicket.isNullOrBlank()) {
                    AuthResult.MfaRequired(body.mfaTicket)
                } else if (!body.accessToken.isNullOrBlank()) {
                    tokenStore.accessToken = body.accessToken
                    AuthResult.Authenticated
                } else {
                    tokenStore.serverUrl = previousUrl
                    AuthResult.Error("Некорректный ответ авторизации")
                }
            } else {
                tokenStore.serverUrl = previousUrl
                AuthResult.Error(parseError(response.errorBody()?.string()))
            }
        } catch (_: Exception) {
            tokenStore.serverUrl = previousUrl
            AuthResult.Error("Ошибка сети: проверьте адрес сервера и TLS")
        }
    }

    suspend fun verifyTotp(mfaTicket: String, code: String): AuthResult {
        return try {
            val response = authApi.verifyMfa(MfaVerifyRequest(mfaTicket, code.trim()))
            if (response.isSuccessful) {
                val token = response.body()?.accessToken
                if (!token.isNullOrBlank()) {
                    tokenStore.accessToken = token
                    AuthResult.Authenticated
                } else {
                    AuthResult.Error("Токен не получен")
                }
            } else {
                AuthResult.Error(parseError(response.errorBody()?.string()))
            }
        } catch (_: Exception) {
            AuthResult.Error("Ошибка проверки TOTP")
        }
    }

    private fun parseError(raw: String?): String {
        if (raw.isNullOrBlank()) return "Ошибка авторизации"
        return try {
            json.decodeFromString<ProblemDetails>(raw).detail ?: "Ошибка авторизации"
        } catch (_: Exception) {
            "Ошибка авторизации"
        }
    }
}
