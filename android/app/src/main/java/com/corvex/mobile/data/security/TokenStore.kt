package com.corvex.mobile.data.security

interface TokenStore {
    var serverUrl: String?
    var accessToken: String?
    var refreshCookieName: String?
    var refreshCookieValue: String?
    var refreshCookiePath: String?
    var refreshCookieExpiresAtMs: Long?
    var pinnedSpkiSha256: String?

    var accountType: String?
    var imapHost: String?
    var imapPort: Int
    var smtpHost: String?
    var smtpPort: Int
    var imapEmail: String?
    var imapPassword: String?

    fun isLoggedIn(): Boolean = !accessToken.isNullOrBlank() || !imapEmail.isNullOrBlank()

    fun clearAuth()
}
