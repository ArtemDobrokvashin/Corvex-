package com.corvex.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.imap.ImapMailService
import com.corvex.mobile.data.imap.OutgoingMail
import com.corvex.mobile.data.imap.SmtpMailService
import com.corvex.mobile.data.repository.MailRepository
import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.AccountType
import com.corvex.mobile.domain.mail.Flag
import com.corvex.mobile.domain.mail.FolderModel
import com.corvex.mobile.domain.mail.MailModel
import com.corvex.mobile.ui.common.MailFormatting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val accountLabel: String = "",
    val selectedFolder: String = "INBOX",
    val folders: List<FolderModel> = emptyList(),
    val mails: List<MailModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val openedMail: MailModel? = null,
    /** Тело открытого письма. `null` — ещё грузится; пустая строка — письмо без текста. */
    val openedBody: String? = null,
    val openedBodyError: String? = null,
    /** `Message-ID` открытого письма: без него ответ не соберётся в цепочку. */
    val openedMessageId: String? = null,
    /** Открыт экран написания: `null` — закрыт. */
    val compose: ComposeState? = null
)

/**
 * Черновик на экране. Отдельный тип, потому что у него своё состояние отправки:
 * черновик может быть открыт, пока письмо отправляется, и ошибка отправки
 * не должна закрывать то, что человек написал.
 */
data class ComposeState(
    val kind: ComposeKind,
    val to: String = "",
    val cc: String = "",
    val subject: String = "",
    val body: String = "",
    val originalId: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    val isSending: Boolean = false,
    val error: String? = null
)

enum class ComposeKind { NEW, REPLY, REPLY_ALL, FORWARD }

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val imapMailService: ImapMailService,
    private val smtpMailService: SmtpMailService,
    private val mailRepository: MailRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            accountLabel = tokenStore.imapEmail ?: tokenStore.serverUrl ?: "Corvex"
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        // Кэш последнего успешного ответа. Сеть на мобильном медленная и рвётся,
        // а пустой экран при каждом запуске — худшее из возможных состояний.
        refresh()
    }

    /** Папки и письма, показанные в прошлый раз: список появляется до сети. */
    private object Cache {
        var folders: List<FolderModel> = emptyList()
        val mails: MutableMap<String, List<MailModel>> = mutableMapOf()
    }

    fun selectFolder(folderId: String) {
        // Снимок из кэша — мгновенно, до похода в сеть. Иначе переключение
        // папки показывает пустой список и «крутилку» на несколько секунд.
        _uiState.update {
            it.copy(
                selectedFolder = folderId,
                mails = Cache.mails[folderId].orEmpty(),
                error = null
            )
        }
        refresh()
    }

    fun refresh() {
        val isImap = tokenStore.accountType == AccountType.IMAP.name
        val folder = _uiState.value.selectedFolder
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = it.mails.isEmpty(),
                    error = null,
                    folders = it.folders.ifEmpty { Cache.folders },
                    mails = it.mails.ifEmpty { Cache.mails[folder].orEmpty() }
                )
            }
            if (isImap) {
                val res = imapMailService.fetchFoldersAndInbox(folder)
                res.onSuccess { (folders, mails) ->
                    Cache.folders = folders
                    Cache.mails[folder] = mails
                    _uiState.update { it.copy(isLoading = false, folders = folders, mails = mails) }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // Если в кэше что-то есть, ошибка не должна стирать список:
                            // человек продолжает читать то, что уже загрузилось.
                            error = err.message ?: "Ошибка загрузки писем"
                        )
                    }
                }
            } else {
                try {
                    mailRepository.syncFolders()
                    _uiState.update { it.copy(isLoading = false) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(isLoading = false, error = "Ошибка синхронизации Corvex") }
                }
            }
        }
    }

    /**
     * Открыть письмо. Экран показывается сразу с тем, что уже есть в списке,
     * а тело подгружается следом — иначе переход ждал бы сеть и выглядел как
     * зависание.
     */
    fun open(mail: MailModel) {
        _uiState.update {
            it.copy(
                openedMail = mail,
                openedBody = null,
                openedBodyError = null,
                openedMessageId = null
            )
        }
        viewModelScope.launch {
            val res = imapMailService.fetchBody(mail.folderId, mail.uid)
            res.onSuccess { body ->
                _uiState.update {
                    it.copy(openedBody = body.bodyText, openedMessageId = body.messageId)
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(openedBodyError = err.message ?: "Не удалось загрузить письмо")
                }
            }
        }
    }

    /** Прочитано / непрочитано. Список обновляется сразу, сервер — следом. */
    fun toggleRead(mail: MailModel) {
        val target = !mail.isRead
        patchMail(mail.id) { it.copy(isRead = target) }
        viewModelScope.launch {
            imapMailService.setFlag(mail.folderId, mail.uid, Flag.SEEN, target)
                .onFailure { err ->
                    // Откат: локальная отметка без серверной соврёт при следующей загрузке.
                    patchMail(mail.id) { it.copy(isRead = mail.isRead) }
                    _uiState.update { it.copy(error = err.message ?: "Не удалось отметить письмо") }
                }
        }
    }

    /** Звёздочка. Как и прочтение — оптимистично, с откатом при ошибке. */
    fun toggleStar(mail: MailModel) {
        val target = !mail.isStarred
        patchMail(mail.id) { it.copy(isStarred = target) }
        viewModelScope.launch {
            imapMailService.setFlag(mail.folderId, mail.uid, Flag.FLAGGED, target)
                .onFailure { err ->
                    patchMail(mail.id) { it.copy(isStarred = mail.isStarred) }
                    _uiState.update { it.copy(error = err.message ?: "Не удалось изменить звёздочку") }
                }
        }
    }

    /** Одна строка списка без перезагрузки всей папки. */
    private fun patchMail(id: String, transform: (MailModel) -> MailModel) {
        _uiState.update { state ->
            state.copy(
                mails = state.mails.map { if (it.id == id) transform(it) else it },
                openedMail = state.openedMail?.let { if (it.id == id) transform(it) else it }
            )
        }
    }

    // --- Экран написания -----------------------------------------------------

    fun composeNew() {
        _uiState.update {
            it.copy(compose = ComposeState(kind = ComposeKind.NEW))
        }
    }

    fun composeReply(mail: MailModel, all: Boolean) {
        val body = _uiState.value.openedBody.orEmpty()
        val senderEmail = addressOf(mail.sender)
        val replyBody = quoted(body, mail.sender)
        _uiState.update {
            it.copy(
                compose = ComposeState(
                    kind = if (all) ComposeKind.REPLY_ALL else ComposeKind.REPLY,
                    to = senderEmail,
                    subject = withReplyPrefix(mail.subject),
                    body = replyBody,
                    originalId = mail.id,
                    inReplyTo = _uiState.value.openedMessageId
                )
            )
        }
    }

    fun composeForward(mail: MailModel) {
        val body = _uiState.value.openedBody.orEmpty()
        _uiState.update {
            it.copy(
                compose = ComposeState(
                    kind = ComposeKind.FORWARD,
                    subject = withForwardPrefix(mail.subject),
                    body = "\n\n---------- Пересланное письмо ----------\n" +
                        "От: ${mail.sender}\n" +
                        "Тема: ${mail.subject}\n\n$body",
                    originalId = mail.id
                )
            )
        }
    }

    fun updateCompose(transform: (ComposeState) -> ComposeState) {
        _uiState.update { state -> state.compose?.let { state.copy(compose = transform(it)) } ?: state }
    }

    fun closeCompose() {
        _uiState.update { it.copy(compose = null) }
    }

    /**
     * Отправить. Черновик остаётся открытым до успеха: при ошибке SMTP человек
     * не должен потерять написанный текст.
     */
    fun sendCompose() {
        val draft = _uiState.value.compose ?: return
        if (draft.isSending) return
        val to = splitAddresses(draft.to)
        if (to.isEmpty()) {
            updateCompose { it.copy(error = "Укажите получателя") }
            return
        }

        updateCompose { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            val res = smtpMailService.send(
                OutgoingMail(
                    to = to,
                    cc = splitAddresses(draft.cc),
                    subject = draft.subject,
                    body = draft.body,
                    inReplyTo = draft.inReplyTo
                )
            )
            res.onSuccess {
                _uiState.update { it.copy(compose = null) }
                refresh()
            }.onFailure { err ->
                updateCompose {
                    it.copy(
                        isSending = false,
                        error = err.message ?: "Не удалось отправить письмо"
                    )
                }
            }
        }
    }

    // --- Перемещение писем ---------------------------------------------------

    /** В архив. Имена папок у провайдеров разные, поэтому список кандидатов. */
    fun archive(mail: MailModel) {
        viewModelScope.launch {
            val res = imapMailService.moveTo(
                mail.folderId,
                mail.uid,
                listOf("Archive", "Archives", "[Gmail]/All Mail", "Архив")
            )
            res.onSuccess {
                removeLocally(mail)
            }.onFailure { err ->
                _uiState.update { it.copy(error = err.message ?: "Не удалось перенести в архив") }
            }
        }
    }

    fun delete(mail: MailModel) {
        viewModelScope.launch {
            val res = imapMailService.moveTo(
                mail.folderId,
                mail.uid,
                listOf("Trash", "[Gmail]/Trash", "Удалённые", "Корзина")
            )
            res.onSuccess {
                removeLocally(mail)
            }.onFailure { err ->
                _uiState.update { it.copy(error = err.message ?: "Не удалось удалить письмо") }
            }
        }
    }

    /** Убирает письмо из списка и закрывает его, если оно было открыто. */
    private fun removeLocally(mail: MailModel) {
        _uiState.update { state ->
            val wasOpen = state.openedMail?.id == mail.id
            state.copy(
                mails = state.mails.filterNot { it.id == mail.id },
                openedMail = if (wasOpen) null else state.openedMail,
                openedBody = if (wasOpen) null else state.openedBody,
                openedMessageId = if (wasOpen) null else state.openedMessageId
            )
        }
    }

    fun closeOpenedMail() {
        _uiState.update {
            it.copy(
                openedMail = null,
                openedBody = null,
                openedBodyError = null,
                openedMessageId = null
            )
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        tokenStore.clearAuth()
        onLoggedOut()
    }
}

/** Адрес из «Имя <a@b.ru>»; пустая строка, если адреса нет. */
internal fun addressOf(sender: String?): String {
    val raw = sender?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    if (raw.contains('<')) return raw.substringAfter('<').substringBefore('>').trim()
    return if (raw.contains('@')) raw else ""
}

/** «a@b.ru, c@d.ru» → список адресов без пустых и без повторов. */
internal fun splitAddresses(value: String): List<String> =
    value.split(',', ';', ' ')
        .map { it.trim() }
        .filter { it.contains('@') }
        .distinctBy { it.lowercase() }

/** «Re: Тема», но без «Re: Re:». */
internal fun withReplyPrefix(subject: String): String =
    if (subject.trim().lowercase().startsWith("re:")) subject else "Re: $subject"

/** «Fwd: Тема», но без «Fwd: Fwd:». */
internal fun withForwardPrefix(subject: String): String {
    val lower = subject.trim().lowercase()
    return if (lower.startsWith("fwd:") || lower.startsWith("fw:")) subject else "Fwd: $subject"
}

/** Цитата исходного письма под ответом — как это делает любой почтовый клиент. */
internal fun quoted(body: String, sender: String): String {
    val quotedBody = body.lines().joinToString("\n") { "> $it" }
    val who = MailFormatting.displayName(sender).ifBlank { "Отправитель" }
    return "\n\n$who пишет:\n$quotedBody"
}
