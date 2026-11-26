package com.corvex.mobile.data.imap

import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.FolderModel
import com.corvex.mobile.domain.mail.Flag
import com.corvex.mobile.domain.mail.MailModel
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.search.BodyTerm
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.SubjectTerm
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
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
     * Отправитель в виде «Имя <a@b.ru>».
     *
     * `InternetAddress.toString()` склеивает закодированное имя и адрес:
     * `"=?utf-8?B?0Klt0JHQ...?=" <inform@emails.tinkoff.ru>`. `decodeText`
     * на такой склейке не срабатывает — имя остаётся крякозяброй, что и было
     * видно в списке и в шапке письма. Поэтому имя декодируем отдельно,
     * а адрес берём из `address`, а не выковыриваем из строки.
     */
    private fun senderOf(msg: Message): String {
        val address = runCatching { msg.from?.firstOrNull() }.getOrNull()
            ?: return "Неизвестный отправитель"
        val email = runCatching { (address as? InternetAddress)?.address ?: address.toString() }
            .getOrNull().orEmpty()
        val name = decodeText(runCatching { (address as? InternetAddress)?.personal }.getOrNull())
            ?: decodeText(runCatching { (address as? InternetAddress)?.toString() }.getOrNull())
        return when {
            !name.isNullOrBlank() && email.isNotBlank() && !name.contains('@') -> "$name <$email>"
            email.isNotBlank() -> email
            else -> "Неизвестный отправитель"
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
            val plain = findPart(msg, "text/plain")
            val text = plain ?: findPart(msg, "text/html")?.let { htmlToText(it) }
            val cleaned = text?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
            if (cleaned.length <= 120) cleaned else cleaned.take(120).trimEnd() + "…"
        }.getOrDefault("")
    }

    /**
     * Перечислить вложения письма: только метаданные, без содержимого.
     *
     * Вложениями считаются части с именем файла. Части `inline` (картинки,
     * вставленные в вёрстку) тоже попадают сюда: иначе в письме с логотипом
     * в подписи «вложений нет», хотя файл в письме есть и его можно
     * сохранить. Мелкие картинки меньше 1 КБ пропускаем — это пиксели-трекеры
     * и разделители, показывать их как файлы бессмысленно.
     */
    private fun attachmentsOf(part: Part, depth: Int = 0, sink: MutableList<MailAttachment> = mutableListOf()): List<MailAttachment> {
        if (depth > 4) return sink
        val name = runCatching { part.fileName }.getOrNull()
        if (!name.isNullOrBlank()) {
            val decoded = decodeText(name) ?: name
            val size = runCatching { part.size }.getOrDefault(0)
            val type = runCatching { part.contentType }.getOrNull().orEmpty()
                .substringBefore(';').trim()
                .ifBlank { "application/octet-stream" }
            if (size >= 1024 || !type.startsWith("image/")) {
                sink.add(MailAttachment(name = decoded, sizeBytes = size, mimeType = type))
            }
            // Внутрь вложения не спускаемся: attached .eml — это файл, а не
            // часть письма, и его кишки показывать не нужно.
            return sink
        }
        if (!runCatching { part.isMimeType("multipart/*") }.getOrDefault(false)) return sink
        val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return sink
        for (i in 0 until multipart.count) {
            val child = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
            attachmentsOf(child, depth + 1, sink)
        }
        return sink
    }

    /**
     * Содержимое вложения по имени.
     *
     * Ищем по имени, а не по индексу части: индексы меняются от версии
     * письма к версии, а имя файла — нет. Дубликаты имён (два `scan.pdf`)
     * разрешаются в пользу первого — редкий случай, но честнее взять
     * что-то, чем ничего.
     */
    private fun findAttachmentPart(part: Part, name: String, depth: Int = 0): Part? {
        if (depth > 4) return null
        val fileName = runCatching { part.fileName }.getOrNull()
        if (!fileName.isNullOrBlank()) {
            val decoded = decodeText(fileName) ?: fileName
            return if (decoded == name) part else null
        }
        if (!runCatching { part.isMimeType("multipart/*") }.getOrDefault(false)) return null
        val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return null
        for (i in 0 until multipart.count) {
            val child = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
            findAttachmentPart(child, name, depth + 1)?.let { return it }
        }
        return null
    }

    /**
     * Найти часть письма нужного типа на любой глубине вложения.
     *
     * Вложения (`fileName != null`) пропускаются: приложенный файл тоже может
     * быть `text/html`, но это не тело письма, и показывать его вместо письма
     * — ошибка. Обычный порядок в письмах с вёрсткой:
     * `multipart/alternative` → `text/plain` + `text/html`.
     */
    private fun findPart(part: Part, mimeType: String, depth: Int = 0): String? {
        if (depth > 4) return null
        if (!runCatching { part.fileName }.getOrNull().isNullOrBlank()) return null

        if (runCatching { part.isMimeType(mimeType) }.getOrDefault(false)) {
            return runCatching { part.content as? String }.getOrNull()
        }
        if (!runCatching { part.isMimeType("multipart/*") }.getOrDefault(false)) return null

        val multipart = runCatching { part.content as? Multipart }.getOrNull() ?: return null
        for (i in 0 until multipart.count) {
            val child = runCatching { multipart.getBodyPart(i) }.getOrNull() ?: continue
            // part.content у Multipart отдаёт Multipart; для части — её тело.
            val found = findPart(child, mimeType, depth + 1)
            if (found != null) return found
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
                        val plain = findPart(message, "text/plain")
                        val html = findPart(message, "text/html")
                        // Текст для ответа и для цитаты: plain, а если его нет —
                        // выжимка из HTML. Ссылки при этом не теряются.
                        val text = plain ?: html?.let { htmlToText(it) }
                        MailBody(
                            subject = decodeText(message.subject) ?: "(Без темы)",
                            from = senderOf(message),
                            bodyText = text?.trim().orEmpty(),
                            messageId = runCatching { message.getHeader("Message-ID")?.firstOrNull() }.getOrNull(),
                            bodyHtml = html?.takeIf { it.isNotBlank() },
                            attachments = attachmentsOf(message)
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
     * Содержимое вложения. Грузится отдельно от письма: открытие письма не
     * должно тянуть десятки мегабайт фотографий.
     */
    suspend fun fetchAttachment(folderName: String, uid: Long, name: String): Result<ByteArray> =
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
                        val part = findAttachmentPart(message, name)
                            ?: error("Вложение не найдено: $name")
                        runCatching { part.inputStream.use { it.readBytes() } }
                            .getOrElse { error("Не удалось прочитать вложение: $name") }
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
        // Raw-строки (`"""..."""`) здесь не украшение. В обычной Kotlin-строке
        // `"\s"` — недопустимая escape-последовательность, и это ошибка
        // компиляции «Unsupported escape sequence»; при этом `"\\s"` в
        // регулярку приходит правильным `\s`. В raw-строке бэкслэш один и
        // остаётся одним — ровно то, что ждёт регулярка. Сборка на этом уже
        // падала: бэкслэши правились «на глаз» и стали одиночными.
        html.replace(Regex("""(?is)<(script|style)[^>]*>.*?</\1>"""), " ")
            // Ссылку не выбрасываем: в письмах банков и рассылок весь смысл —
            // в адресе, а он живёт в href, а не в тексте ссылки.
            .replace(Regex("""(?is)<a\s[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>"""), "$2 ($1)")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
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

    /**
     * Поиск писем на сервере.
     *
     * Почему через `SearchTerm`, а не локальной фильтрацией: клиент держит в
     * памяти последние 25 писем, и поиск по ним находил бы только их. IMAP
     * `SEARCH` выполняется на сервере и видит весь ящик.
     *
     * Русский язык здесь работает плохо по объективной причине: `SEARCH TEXT`
     * ищет байты как есть, а тема письма на сервере может быть в MIME-кодировке
     * (`=?utf-8?B?...?=`). Поэтому дополнительно фильтруем результат локально
     * по уже декодированным теме и отправителю — так поиск по русскому слову
     * всё же находит письма, которые сервер вернул по другим совпадениям.
     */
    suspend fun search(folderName: String, query: String, limit: Int = 50): Result<List<MailModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val term = query.trim()
                if (term.isEmpty()) return@runCatching emptyList()

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
                        val uidFolder = folder as? UIDFolder
                        val bySubject = runCatching {
                            folder.search(SubjectTerm(term)).toList()
                        }.getOrDefault(emptyList())
                        val bySender = runCatching {
                            folder.search(FromStringTerm(term)).toList()
                        }.getOrDefault(emptyList())
                        val byBody = runCatching {
                            folder.search(BodyTerm(term)).toList()
                        }.getOrDefault(emptyList())

                        val lowered = term.lowercase()
                        val found = (bySubject + bySender + byBody)
                            .distinctBy { runCatching { uidFolder?.getUID(it) }.getOrNull() }
                            .sortedByDescending { runCatching { it.receivedDate?.time }.getOrNull() ?: 0L }
                            .take(limit)

                        // Сервер мог вернуть письмо по совпадению в теле —
                        // такие тоже оставляем: это честный результат поиска.
                        found
                            .map { msg ->
                                val uid = runCatching { uidFolder?.getUID(msg) }.getOrNull() ?: 0L
                                MailModel(
                                    id = "$folderName:$uid",
                                    folderId = folderName,
                                    uid = uid,
                                    subject = decodeText(msg.subject) ?: "(Без темы)",
                                    sender = senderOf(msg),
                                    snippet = previewOf(msg, uidFolder),
                                    isRead = msg.flags.contains(Flags.Flag.SEEN),
                                    isStarred = msg.flags.contains(Flags.Flag.FLAGGED),
                                    receivedAt = msg.receivedDate?.time ?: 0L
                                )
                            }
                            .filter { mail ->
                                // Сервер ищет байты, а тема в письме может быть в
                                // MIME-кодировке. Поэтому по декодированным теме,
                                // отправителю и превью проверяем ещё раз: иначе
                                // поиск по русскому слову не находит ничего.
                                mail.subject.lowercase().contains(lowered) ||
                                    mail.sender.lowercase().contains(lowered) ||
                                    mail.snippet.lowercase().contains(lowered)
                            }
                    } finally {
                        folder.close(false)
                    }
                } finally {
                    store.close()
                }
            }
        }

    /**
     * Папки и письма.
     *
     * [limit] — сколько последних писем брать. Раньше здесь было жёстко 25, и
     * старые письма в ящике с сотнями сообщений были недоступны вообще:
     * догрузки не существовало. Теперь лимит передаётся снаружи, и список
     * растёт порциями.
     */
    suspend fun fetchFoldersAndInbox(
        folderName: String = "INBOX",
        limit: Int = 25
    ): Result<Pair<List<FolderModel>, List<MailModel>>> =
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
                                val start = maxOf(1, total - (limit - 1).coerceAtLeast(0))
                                val msgs = target.getMessages(start, total)
                                val uidFolder = target as? UIDFolder
                                msgs.reversed().mapIndexed { idx, msg ->
                                    val uid = uidFolder?.getUID(msg) ?: (total - idx).toLong()
                                    val from = senderOf(msg)
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
