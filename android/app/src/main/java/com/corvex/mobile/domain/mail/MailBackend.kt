package com.corvex.mobile.domain.mail

import kotlinx.coroutines.flow.Flow

enum class AccountType { CORVEX, IMAP }

enum class Security { SSL_TLS, STARTTLS, NONE }

enum class AuthKind { PASSWORD, APP_PASSWORD }

enum class PushKind { NONE, IDLE, BACKGROUND_FETCH }

enum class Flag { SEEN, FLAGGED }

data class Account(
    val id: String,
    val type: AccountType,
    val email: String,
    val displayName: String,
    val color: Int,
    val syncIntervalMinutes: Int = 15
)

data class ImapConfig(
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: Security,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSecurity: Security,
    val username: String,
    val authKind: AuthKind
)

data class Capabilities(
    val serverSearch: Boolean = false,
    val canSend: Boolean = false,
    val push: PushKind = PushKind.NONE,
    val specialUseFolders: Boolean = false,
    val labels: Boolean = false,
    val chat: Boolean = false,
    val calendar: Boolean = false,
    val drive: Boolean = false,
    val serverPreview: Boolean = false
)

data class FolderModel(
    val id: String,
    val name: String,
    val specialUse: String? = null,
    val unreadCount: Int = 0
)

data class MailModel(
    val id: String,
    val folderId: String,
    val uid: Long,
    val subject: String,
    val sender: String,
    val snippet: String,
    val bodyHtml: String? = null,
    val bodyText: String? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val receivedAt: Long = 0L
)

data class Cursor(val uid: Long)
data class Query(val text: String)
data class Draft(val to: List<String>, val subject: String, val bodyText: String)
data class SendResult(val accepted: Boolean, val messageId: String? = null)

interface MailBackend {
    val capabilities: Capabilities
    fun folders(): Flow<List<FolderModel>>
    fun messages(folderId: String, cursor: Cursor?, limit: Int): Flow<List<MailModel>>
    suspend fun message(mailId: String): MailModel
    suspend fun setFlag(mailId: String, flag: Flag, value: Boolean)
    suspend fun move(mailId: String, targetFolderId: String)
    suspend fun search(query: Query): List<MailModel>
    suspend fun send(draft: Draft): SendResult
}
