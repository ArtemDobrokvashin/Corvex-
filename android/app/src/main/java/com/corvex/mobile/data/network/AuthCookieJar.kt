package com.corvex.mobile.data.network

import com.corvex.mobile.data.security.SecureStorage
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthCookieJar @Inject constructor(
    private val secureStorage: SecureStorage
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        // Сохраняем только HttpOnly refresh-токен, не логируя его содержимое
        val refreshCookie = cookies.firstOrNull { it.httpOnly && it.name.contains("refresh", ignoreCase = true) }
            ?: cookies.firstOrNull { it.httpOnly }
        if (refreshCookie != null) {
            secureStorage.refreshCookie = "${refreshCookie.name}=${refreshCookie.value}"
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = secureStorage.refreshCookie ?: return emptyList()
        val parts = raw.split("=", limit = 2)
        if (parts.size != 2) return emptyList()

        return listOf(
            Cookie.Builder()
                .name(parts[0])
                .value(parts[1])
                .domain(url.host)
                .path("/")
                .httpOnly()
                .secure()
                .build()
        )
    }
}
