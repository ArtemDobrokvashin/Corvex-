package com.corvex.mobile.data.imap

import com.corvex.mobile.data.security.TokenStore
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Письмо на отправку. Отдельный тип, а не `Draft` из домена: здесь уже
 * разобранные адреса и заголовки для ответа, а `Draft` — то, что вводит человек.
 */
data class OutgoingMail(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    /** `Message-ID` письма, на которое отвечаем: с ним переписка собирается в цепочку. */
    val inReplyTo: String? = null,
    val references: String? = null
)

/**
 * Отправка через SMTP.
 *
 * До этого в клиенте не было отправки вообще: `ImapMailService` умел только
 * читать. Порт 465 — SMTPS (TLS с первого байта), 587 — SMTP + STARTTLS.
 * Пароль берётся тот же, что для IMAP: у mail.ru это пароль приложения, и он
 * один на оба протокола.
 */
@Singleton
class SmtpMailService @Inject constructor(
    private val tokenStore: TokenStore
) {
    private fun session(host: String, port: Int): Session {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", host)
            put("mail.smtp.port", port.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
            if (port == 465) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            } else {
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.starttls.required", "true")
                put("mail.smtp.ssl.checkserveridentity", "true")
            }
        }
        return Session.getInstance(props)
    }

    suspend fun send(mail: OutgoingMail): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val host = tokenStore.smtpHost ?: error("SMTP-сервер не настроен")
            val port = tokenStore.smtpPort
            val email = tokenStore.imapEmail ?: error("Адрес почты не настроен")
            val password = tokenStore.imapPassword ?: error("Пароль не сохранён")

            if (mail.to.isEmpty()) error("Укажите хотя бы одного получателя")

            val session = session(host, port)
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(email))
                setRecipients(Message.RecipientType.TO, mail.to.toAddresses())
                if (mail.cc.isNotEmpty()) {
                    setRecipients(Message.RecipientType.CC, mail.cc.toAddresses())
                }
                subject = mail.subject.ifBlank { "(без темы)" }
                setText(mail.body, "UTF-8")
                setSentDate(Date())
                mail.inReplyTo?.let { setHeader("In-Reply-To", it) }
                mail.references?.let { setHeader("References", it) }
            }

            val transport = session.getTransport("smtp")
            try {
                transport.connect(host, port, email, password)
                transport.sendMessage(message, message.allRecipients)
            } finally {
                runCatching { transport.close() }
            }
        }
    }

    private fun List<String>.toAddresses(): Array<InternetAddress> =
        map { InternetAddress(it.trim(), true) }.toTypedArray()
}
