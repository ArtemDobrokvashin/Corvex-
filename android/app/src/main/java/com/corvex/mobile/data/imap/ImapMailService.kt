package com.corvex.mobile.data.imap

import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.FolderModel
import com.corvex.mobile.domain.mail.Flag
import com.corvex.mobile.domain.mail.MailModel
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImapMailService @Inject constructor(
    private val tokenStore: TokenStore
) {
    private fun createSession(host: String, port: Int): Session {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.ssl.checkserveridentity", "true")
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "15000")
            put("mail.imaps.partialfetch", "true")
        }
        return Session.getInstance(props)
    }

    suspend fun verifyConnection(host: String, port: Int, email: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = createSession(host, port)
                val store: Store = session.getStore("imaps")
                store.connect(host, port, email, password)
                store.close()
            }
        }

    /**
     * Декодирует заголовок письма. `MimeUtility.decodeText` разбирает и
     * `=?utf-8?B?...?=`, и «сырые» заголовки; исключение здесь ожидаемо на
     * кривых письмах, и лучше показать исходную строку, чем уронить список.
     */
    private fun decodeText(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return runCatching { MimeUtility.decodeText(raw) }.getOrDefault(raw)
    }

    /**
     * Превью для строки списка. Раньше `snippet` был пустой строкой, и в списке
     * не было ничего, кроме темы. Берём до 120 символов текста без загрузки
     * вложений: `getContent()` на multipart вернёт текст, картинки при этом
     * не скачиваются целиком.
     */
    private fun previewOf(msg: Message, uidFolder: UIDFolder?): String {
        return runCatching {
            val text = when (val content = msg.content) {
                is String -> content
                is Multipart -> firstTextPart(content)
                else -> null
            }
            val cleaned = text?.replace(Regex("\s+"), " ")?.trim().orEmpty()
            if (cleaned.length <= 120) cleaned else cleaned.take(120).trimEnd() + "…"
        }.getOrDefault("")
    }

    private fun firstTextPart(multipart: Multipart, depth: Int = 0): String? {
        if (depth > 3) return null
        for (i in 0 until multipart.count) {
            val part = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
            val fileName = runCatching { part.fileName }.getOrNull()
            if (!fileName.isNullOrBlank()) continue
            when {
                part.isMimeType("text/plain") ->
                    return runCatching { part.content as? String }.getOrNull()
                part.isMimeType("multipart/*") ->
                    (runCatching { part.content as? Multipart }.getOrNull())
                        ?.let { nested -> firstTextPart(nested, depth + 1)?.let { return it } }
            }
        }
        // HTML-письма без text/plain: грубо снимаем теги, чтобы список не был пустым.
        for (i in 0 until multipart.count) {
            val part = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
            if (!part.isMimeType("text/html")) continue
            val html = runCatching { part.content as? String }.getOrNull() ?: continue
            return html.replace(Regex("<[^>]+>"), " ")
        }
        return null
    }

    /**
     * Тело письма целиком — по требованию, при открытии. Список его не тянет:
     * 25 писем с полными телами и вложениями — это секунды ожидания на каждом
     * обновлении.
     */
    suspend fun fetchBody(folderName: String, uid: Long): Result<MailBody> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = tokenStore.imapHost ?: error("IMAP host not configured")
                val port = tokenStore.imapPort
                val email = tokenStore.imapEmail ?: error("IMAP email not configured")
                val password = tokenStore.imapPassword ?: error("IMAP password not configured")

                val session = createSession(host, port)
                val store = session.getStore("imaps")
                store.connect(host, port, email, password)
                try {
                    val folder = store.getFolder(folderName)
                    if (!folder.exists()) error("Папка не найдена: $folderName")
                    folder.open(Folder.READ_ONLY)
                    try {
                        val message = (folder as? UIDFolder)?.getMessageByUID(uid)
                            ?: error("Письмо не найдено")
                        val content = message.content
                        val text = when (content) {
                            is String -> if (message.isMimeType("text/html")) htmlToText(content) else content
                            is Multipart -> firstTextPart(content)
                            else -> null
                        }
                        MailBody(
                            subject = decodeText(message.subject) ?: "(Без темы)",
                            from = decodeText(message.from?.firstOrNull()?.toString()),
                            bodyText = text?.trim().orEmpty(),
                            messageId = runCatching { message.getHeader("Message-ID")?.firstOrNull() }.getOrNull()
                        )
                    } finally {
                        folder.close(false)
                    }
                } finally {
                    store.close()
                }
            }
        }

    /**
     * Флаг на сервере: SEEN (прочитано) или FLAGGED (звёздочка). До этого
     * клиент умел только читать: отметка оставалась локальной и пропадала
     * при следующей загрузке.
     */
    suspend fun setFlag(folderName: String, uid: Long, flag: Flag, value: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = tokenStore.imapHost ?: error("IMAP host not configured")
                val port = tokenStore.imapPort
                val email = tokenStore.imapEmail ?: error("IMAP email not configured")
                val password = tokenStore.imapPassword ?: error("IMAP password not configured")

                val session = createSession(host, port)
                val store = session.getStore("imaps")
                store.connect(host, port, email, password)
                try {
                    val folder = store.getFolder(folderName)
                    // READ_WRITE обязателен: в READ_ONLY IMAP не принимает изменения флагов.
                    folder.open(Folder.READ_WRITE)
                    try {
                        val message = (folder as? UIDFolder)?.getMessageByUID(uid)
                            ?: error("Письмо не найдено")
                        val jmapFlag = when (flag) {
                            Flag.SEEN -> Flags.Flag.SEEN
                            Flag.FLAGGED -> Flags.Flag.FLAGGED
                        }
                        message.setFlags(Flags(jmapFlag), value)
                    } finally {
                        folder.close(true)
                    }
                } finally {
                    store.close()
                }
            }
        }

    /**
     * Перенести письмо в другую папку (архив, спам, корзина).
     *
     * IMAP не умеет «переместить» одним действием: копируем в целевую папку и
     * ставим `DELETED` в исходной. Целевую папку ищем по имени: `Archive`
     * у mail.ru, `[Gmail]/All Mail` у Gmail — точного имени заранее не знает
     * никто, поэтому берём первое совпадение из списка кандидатов.
     */
    suspend fun moveTo(folderName: String, uid: Long, targetCandidates: List<String>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = tokenStore.imapHost ?: error("IMAP host not configured")
                val port = tokenStore.imapPort
                val email = tokenStore.imapEmail ?: error("IMAP email not configured")
                val password = tokenStore.imapPassword ?: error("IMAP password not configured")

                val session = createSession(host, port)
                val store = session.getStore("imaps")
                store.connect(host, port, email, password)
                try {
                    val targetName = targetCandidates.firstOrNull { name ->
                        runCatching { store.getFolder(name).exists() }.getOrDefault(false)
                    } ?: error("Папка не найдена: ${targetCandidates.joinToString()}")

                    val source = store.getFolder(folderName)
                    source.open(Folder.READ_WRITE)
                    val target = store.getFolder(targetName)
                    try {
                        val message = (source as? UIDFolder)?.getMessageByUID(uid)
                            ?: error("Письмо не найдено")
                        // copyMessages копирует, но не удаляет: без второго шага
                        // письмо останется в двух папках сразу.
                        if (!target.exists()) target.create(Folder.HOLDS_MESSAGES)
                        source.copyMessages(arrayOf(message), target)
                        message.setFlag(Flags.Flag.DELETED, true)
                    } finally {
                        // expunge=true — иначе DELETED только помечен и письмо остаётся.
                        source.close(true)
                    }
                    targetName
                } finally {
                    store.close()
                }
            }
        }

    private fun htmlToText(html: String): String =
        // В регулярках здесь один бэкслэш: в Kotlin-строке `\s` — это литерал
        // «обратный слэш + s», и регулярка его не поймёт как пробельный символ.
        html.replace(Regex("(?is)<(script|style)[^>]*>.*?</\1>"), " ")
            .replace(Regex("(?i)<br\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    suspend fun fetchFoldersAndInbox(folderName: String = "INBOX"): Result<Pair<List<FolderModel>, List<MailModel>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = tokenStore.imapHost ?: error("IMAP host not configured")
                val port = tokenStore.imapPort
                val email = tokenStore.imapEmail ?: error("IMAP email not configured")
                val password = tokenStore.imapPassword ?: error("IMAP password not configured")

                val session = createSession(host, port)
                val store = session.getStore("imaps")
                store.connect(host, port, email, password)
                try {
                    val folders = store.defaultFolder.list("*").map { f ->
                        FolderModel(
                            id = f.fullName,
                            name = f.name,
                            unreadCount = runCatching { f.unreadMessageCount }.getOrDefault(0)
                        )
                    }

                    val target = store.getFolder(folderName)
                    val mails = if (target.exists() && (target.type and Folder.HOLDS_MESSAGES) != 0) {
                        target.open(Folder.READ_ONLY)
                        try {
                            val total = target.messageCount
                            if (total == 0) {
                                emptyList()
                            } else {
                                val start = maxOf(1, total - 24)
                                val msgs = target.getMessages(start, total)
                                val uidFolder = target as? UIDFolder
                                msgs.reversed().mapIndexed { idx, msg ->
                                    val uid = uidFolder?.getUID(msg) ?: (total - idx).toLong()
                                    val from = msg.from?.firstOrNull()?.toString() ?: "Неизвестный отправитель"
                                    MailModel(
                                        id = "$folderName:$uid",
                                        folderId = folderName,
                                        uid = uid,
                                        // Тема приходит в MIME-encoded-word («=?utf-8?B?...?=»),
                                        // и без декодирования вместо темы видна эта абракадабра.
                                        subject = decodeText(msg.subject) ?: "(Без темы)",
                                        sender = from,
                                        snippet = previewOf(msg, uidFolder),
                                        isRead = msg.flags.contains(Flags.Flag.SEEN),
                                        isStarred = msg.flags.contains(Flags.Flag.FLAGGED),
                                        receivedAt = msg.receivedDate?.time ?: 0L
                                    )
                                }
                            }
                        } finally {
                            target.close(false)
                        }
                    } else {
                        emptyList()
                    }

                    folders to mails
                } finally {
                    store.close()
                }
            }
        }
}
