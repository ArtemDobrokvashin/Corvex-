package com.corvex.mobile.data.security

interface TokenStore {
    var serverUrl: String?
    var accessToken: String?
    var refreshCookieName: String?
    var refreshCookieValue: String?
    var refreshCookiePath: String?
    var refreshCookieExpiresAtMs: Long?
    var pinnedSpkiSha256: String?

    fun clearAuth()
}
