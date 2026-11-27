package com.corvex.mobile.data.security

import android.util.Base64

/**
 * Чтение `sub` из JWT без проверки подписи.
 *
 * Подпись проверяет сервер — здесь она не нужна и вредна: клиент только
 * показывает «мои» сообщения. `sub` в токене auth-service — это userId
 * (`TokenService`: `.subject(userId.toString())`), поэтому сравнение с
 * `authorId` корректно.
 *
 * При любой проблеме возвращается `null`: чат должен работать и с испорченным
 * токеном (тогда все сообщения просто окажутся «чужими» визуально).
 */
object JwtSubject {
    fun from(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size < 2) return null
        return runCatching {
            val payload = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val json = String(payload, Charsets.UTF_8)
            Regex("\"sub\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
        }.getOrNull()
    }
}
