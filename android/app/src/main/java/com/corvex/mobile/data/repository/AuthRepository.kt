package com.corvex.mobile.data.repository

import com.corvex.mobile.data.network.api.AuthApi
import com.corvex.mobile.data.network.api.LoginRequest
import com.corvex.mobile.data.network.api.MfaVerifyRequest
import com.corvex.mobile.data.network.api.ProblemDetails
import com.corvex.mobile.data.security.SecureStorage
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

sealed class AuthResult {
    data object Authenticated : AuthResult()
    data class MfaRequired(val mfaToken: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val secureStorage: SecureStorage,
    private val json: Json
) {
    suspend fun login(serverUrl: String, email: String, password: String): AuthResult {
        secureStorage.serverUrl = serverUrl.trim()
        return try {
            val response = authApi.login(LoginRequest(email.trim(), password))
            if (response.isSuccessful) {
                val body = response.body() ?: return AuthResult.Error("Пустой ответ сервера")
                if (body.mfaRequired && !body.mfaToken.isNullOrBlank()) {
                    AuthResult.MfaRequired(body.mfaToken)
                } else if (!body.accessToken.isNullOrBlank()) {
                    secureStorage.accessToken = body.accessToken
                    AuthResult.Authenticated
                } else {
                    AuthResult.Error("Некорректный ответ авторизации")
                }
            } else {
                AuthResult.Error(parseError(response.errorBody()?.string()))
            }
        } catch (e: Exception) {
            AuthResult.Error("Ошибка сети: проверьте адрес сервера и TLS")
        }
    }

    suspend fun verifyTotp(mfaToken: String, code: String): AuthResult {
        return try {
            val response = authApi.verifyMfa(MfaVerifyRequest(mfaToken, code.trim()))
            if (response.isSuccessful) {
                val token = response.body()?.accessToken
                if (!token.isNullOrBlank()) {
                    secureStorage.accessToken = token
                    AuthResult.Authenticated
                } else {
                    AuthResult.Error("Токен не получен")
                }
            } else {
                AuthResult.Error(parseError(response.errorBody()?.string()))
            }
        } catch (e: Exception) {
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
