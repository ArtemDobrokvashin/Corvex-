package com.corvex.mobile.data.network

import com.corvex.mobile.data.security.TokenStore
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthCookieJar @Inject constructor(
    private val tokenStore: TokenStore
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val refreshCookie = cookies.firstOrNull {
            it.httpOnly && (it.name == "corvex_refresh" || it.name.contains("refresh", ignoreCase = true))
        } ?: return

        tokenStore.refreshCookieName = refreshCookie.name
        tokenStore.refreshCookieValue = refreshCookie.value
        tokenStore.refreshCookiePath = refreshCookie.path
        tokenStore.refreshCookieExpiresAtMs = if (refreshCookie.persistent) refreshCookie.expiresAt else null
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val name = tokenStore.refreshCookieName ?: return emptyList()
        val value = tokenStore.refreshCookieValue ?: return emptyList()
        val path = tokenStore.refreshCookiePath ?: "/api/v1/auth"
        val expiresAt = tokenStore.refreshCookieExpiresAtMs

        if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
            tokenStore.refreshCookieName = null
            tokenStore.refreshCookieValue = null
            return emptyList()
        }

        if (!url.encodedPath.startsWith(path)) {
            return emptyList()
        }

        return listOf(
            Cookie.Builder()
                .name(name)
                .value(value)
                .domain(url.host)
                .path(path)
                .httpOnly()
                .apply { if (url.isHttps) secure() }
                .build()
        )
    }
}
