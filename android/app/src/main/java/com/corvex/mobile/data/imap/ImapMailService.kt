package com.corvex.mobile.data.imap

import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.FolderModel
import com.corvex.mobile.domain.mail.MailModel
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
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
                                        subject = msg.subject ?: "(Без темы)",
                                        sender = from,
                                        snippet = "",
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
