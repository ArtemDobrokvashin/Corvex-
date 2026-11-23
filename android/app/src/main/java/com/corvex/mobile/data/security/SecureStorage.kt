package com.corvex.mobile.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureStorage @Inject constructor(
    @ApplicationContext context: Context
) : TokenStore {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "corvex_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    override var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    override var refreshCookieName: String?
        get() = prefs.getString(KEY_REFRESH_COOKIE_NAME, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_COOKIE_NAME, value).apply()

    override var refreshCookieValue: String?
        get() = prefs.getString(KEY_REFRESH_COOKIE_VALUE, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_COOKIE_VALUE, value).apply()

    override var refreshCookiePath: String?
        get() = prefs.getString(KEY_REFRESH_COOKIE_PATH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_COOKIE_PATH, value).apply()

    override var refreshCookieExpiresAtMs: Long?
        get() = if (prefs.contains(KEY_REFRESH_COOKIE_EXPIRES)) prefs.getLong(KEY_REFRESH_COOKIE_EXPIRES, 0L) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_REFRESH_COOKIE_EXPIRES).apply()
            else prefs.edit().putLong(KEY_REFRESH_COOKIE_EXPIRES, value).apply()
        }

    override var pinnedSpkiSha256: String?
        get() = prefs.getString(KEY_SPKI_PIN, null)
        set(value) = prefs.edit().putString(KEY_SPKI_PIN, value).apply()

    override var accountType: String?
        get() = prefs.getString(KEY_ACCOUNT_TYPE, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_TYPE, value).apply()

    override var imapHost: String?
        get() = prefs.getString(KEY_IMAP_HOST, null)
        set(value) = prefs.edit().putString(KEY_IMAP_HOST, value).apply()

    override var imapPort: Int
        get() = prefs.getInt(KEY_IMAP_PORT, 993)
        set(value) = prefs.edit().putInt(KEY_IMAP_PORT, value).apply()

    override var smtpHost: String?
        get() = prefs.getString(KEY_SMTP_HOST, null)
        set(value) = prefs.edit().putString(KEY_SMTP_HOST, value).apply()

    override var smtpPort: Int
        get() = prefs.getInt(KEY_SMTP_PORT, 465)
        set(value) = prefs.edit().putInt(KEY_SMTP_PORT, value).apply()

    override var imapEmail: String?
        get() = prefs.getString(KEY_IMAP_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_IMAP_EMAIL, value).apply()

    override var imapPassword: String?
        get() = prefs.getString(KEY_IMAP_PASSWORD, null)
        set(value) = prefs.edit().putString(KEY_IMAP_PASSWORD, value).apply()

    override fun clearAuth() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_COOKIE_NAME)
            .remove(KEY_REFRESH_COOKIE_VALUE)
            .remove(KEY_REFRESH_COOKIE_PATH)
            .remove(KEY_REFRESH_COOKIE_EXPIRES)
            .remove(KEY_ACCOUNT_TYPE)
            .remove(KEY_IMAP_HOST)
            .remove(KEY_IMAP_PORT)
            .remove(KEY_SMTP_HOST)
            .remove(KEY_SMTP_PORT)
            .remove(KEY_IMAP_EMAIL)
            .remove(KEY_IMAP_PASSWORD)
            .apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_COOKIE_NAME = "refresh_cookie_name"
        const val KEY_REFRESH_COOKIE_VALUE = "refresh_cookie_value"
        const val KEY_REFRESH_COOKIE_PATH = "refresh_cookie_path"
        const val KEY_REFRESH_COOKIE_EXPIRES = "refresh_cookie_expires_ms"
        const val KEY_SPKI_PIN = "pinned_spki_sha256"
        const val KEY_ACCOUNT_TYPE = "account_type"
        const val KEY_IMAP_HOST = "imap_host"
        const val KEY_IMAP_PORT = "imap_port"
        const val KEY_SMTP_HOST = "smtp_host"
        const val KEY_SMTP_PORT = "smtp_port"
        const val KEY_IMAP_EMAIL = "imap_email"
        const val KEY_IMAP_PASSWORD = "imap_password"
    }
}
