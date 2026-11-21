package com.corvex.mobile.domain.mail

data class ImapPreset(
    val id: String,
    val title: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: Security,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: Security,
    val hint: String
)

object ImapProviderPresets {
    val presets = listOf(
        ImapPreset(
            id = "mailru",
            title = "Mail.ru",
            imapHost = "imap.mail.ru",
            imapPort = 993,
            imapSecurity = Security.SSL_TLS,
            smtpHost = "smtp.mail.ru",
            smtpPort = 465,
            smtpSecurity = Security.SSL_TLS,
            hint = "Настройки -> Безопасность -> Пароли для внешних приложений"
        ),
        ImapPreset(
            id = "yandex",
            title = "Яндекс",
            imapHost = "imap.yandex.ru",
            imapPort = 993,
            imapSecurity = Security.SSL_TLS,
            smtpHost = "smtp.yandex.ru",
            smtpPort = 465,
            smtpSecurity = Security.SSL_TLS,
            hint = "Включите IMAP в настройках ящика и создайте пароль приложения со скоупом Почта (IMAP, SMTP)"
        ),
        ImapPreset(
            id = "gmail",
            title = "Gmail",
            imapHost = "imap.gmail.com",
            imapPort = 993,
            imapSecurity = Security.SSL_TLS,
            smtpHost = "smtp.gmail.com",
            smtpPort = 465,
            smtpSecurity = Security.SSL_TLS,
            hint = "Gmail: используйте пароль приложения (при включённой 2FA). OAuth — позже."
        )
    )
}
