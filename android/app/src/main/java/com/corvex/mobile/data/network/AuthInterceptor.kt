package com.corvex.mobile.data.network

import com.corvex.mobile.data.security.TokenStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Serializable
data class RefreshResponse(
    val accessToken: String
)

@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,
    @Named("RefreshClient") private val refreshClient: OkHttpClient,
    private val json: Json
) : Interceptor {

    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath
        val skipAuth = path.endsWith("/auth/login") || path.endsWith("/auth/refresh")
        val token = tokenStore.accessToken

        val authenticatedRequest = if (!token.isNullOrBlank() && !skipAuth) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }

        val response = chain.proceed(authenticatedRequest)
        if (response.code != 401 || skipAuth) {
            return response
        }

        response.close()

        val newToken = synchronized(lock) {
            val currentToken = tokenStore.accessToken
            if (currentToken != null && currentToken != token) {
                currentToken
            } else {
                refreshAccessToken(original.url.scheme, original.url.host, original.url.port)
            }
        } ?: run {
            tokenStore.clearAuth()
            return chain.proceed(original)
        }

        val retriedRequest = original.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
        return chain.proceed(retriedRequest)
    }

    private fun refreshAccessToken(scheme: String, host: String, port: Int): String? {
        val baseUrl = "$scheme://$host:$port/api/v1/auth/refresh"
        val request = Request.Builder()
            .url(baseUrl)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            refreshClient.newCall(request).execute().use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string() ?: return null
                val parsed = json.decodeFromString<RefreshResponse>(body)
                tokenStore.accessToken = parsed.accessToken
                parsed.accessToken
            }
        } catch (_: Exception) {
            null
        }
    }
}
