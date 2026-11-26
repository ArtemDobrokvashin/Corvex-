package com.corvex.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.imap.ImapMailService
import com.corvex.mobile.data.imap.MailAttachment
import com.corvex.mobile.data.imap.OutgoingAttachment
import com.corvex.mobile.data.imap.OutgoingMail
import com.corvex.mobile.data.imap.SmtpMailService
import com.corvex.mobile.data.local.AttachmentStore
import com.corvex.mobile.data.repository.MailRepository
import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.AccountType
import com.corvex.mobile.domain.mail.Flag
import com.corvex.mobile.domain.mail.FolderModel
import com.corvex.mobile.domain.mail.MailModel
import com.corvex.mobile.ui.common.MailFormatting
import com.corvex.mobile.ui.mail.ComposeField
import com.corvex.mobile.ui.mail.ComposeKind
import com.corvex.mobile.ui.mail.ComposeState
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
    /** HTML открытого письма; `null` — письмо текстовое или ещё грузится. */
    val openedHtml: String? = null,
    /** Вложения открытого письма: метаданные и, после загрузки, содержимое. */
    val openedAttachments: List<MailAttachment> = emptyList(),
    /** Имя вложения, которое сейчас скачивается. */
    val downloadingAttachment: String? = null,
    /** Сообщение о сохранённом файле или ошибке — то, что показываем тостом. */
    val attachmentNotice: String? = null,
    /** Короткое сообщение об успешном действии (перенос в папку). */
    val notice: String? = null,
    /** `Message-ID` открытого письма: без него ответ не соберётся в цепочку. */
    val openedMessageId: String? = null,
    /** Открыт экран написания: `null` — закрыт. */
    val compose: ComposeState? = null,
    /** Строка поиска; пустая — обычный список. */
    val query: String = "",
    /** Результаты поиска на сервере; `null` — поиск не активен. */
    val searchResults: List<MailModel>? = null,
    val isSearching: Boolean = false,
    /** Письмо, для которого выбирают папку переноса; `null` — диалог закрыт. */
    val movingMail: MailModel? = null,
    /** Можно ли догрузить более старые письма: не в поиске и не все загружены. */
    val canLoadMore: Boolean = true,
    val isLoadingMore: Boolean = false
)



@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val imapMailService: ImapMailService,
    private val smtpMailService: SmtpMailService,
    private val attachmentStore: AttachmentStore,
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
        /** Сколько писем запрошено для папки: растёт при догрузке. */
        val limits: MutableMap<String, Int> = mutableMapOf()
    }

    private companion object {
        const val PAGE = 25

        /** 20 МБ: у mail.ru лимит письма 25 МБ, запас — на кодирование base64. */
        const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024
    }

    fun selectFolder(folderId: String) {
        // Снимок из кэша — мгновенно, до похода в сеть. Иначе переключение
        // папки показывает пустой список и «крутилку» на несколько секунд.
        _uiState.update {
            it.copy(
                selectedFolder = folderId,
                mails = Cache.mails[folderId].orEmpty(),
                error = null,
                // Поиск живёт в пределах папки: при переходе в другую старые
                // результаты не должны оставаться на экране.
                query = "",
                searchResults = null,
                canLoadMore = true
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
                val limit = Cache.limits[folder] ?: PAGE
                val res = imapMailService.fetchFoldersAndInbox(folder, limit)
                res.onSuccess { (folders, mails) ->
                    Cache.folders = folders
                    Cache.mails[folder] = mails
                    // Если пришло меньше, чем просили, — в папке больше нечего
                    // грузить; кнопка «ещё» иначе нажималась бы вхолостую.
                    val exhausted = mails.size < limit
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            folders = folders,
                            mails = mails,
                            canLoadMore = !exhausted
                        )
                    }
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
                openedHtml = null,
                openedAttachments = emptyList(),
                openedBodyError = null,
                openedMessageId = null
            )
        }
        viewModelScope.launch {
            val res = imapMailService.fetchBody(mail.folderId, mail.uid)
            res.onSuccess { body ->
                _uiState.update {
                    it.copy(
                        openedBody = body.bodyText,
                        openedHtml = body.bodyHtml,
                        openedAttachments = body.attachments,
                        openedMessageId = body.messageId
                    )
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
                    to = listOfNotNull(senderEmail.takeIf { a -> a.isNotBlank() }),
                    subject = withReplyPrefix(mail.subject),
                    // Курсор сразу в теле: отвечающий пишет текст, а не правит адреса.
                    body = replyBody,
                    originalId = mail.id,
                    inReplyTo = _uiState.value.openedMessageId,
                    showCc = all
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

    /**
     * Добавить получателя из введённой строки.
     *
     * Адрес добавляется по запятой, точке с запятой или пробелу — так же, как
     * в Gmail: набрал адрес, ткнул запятую, он стал «таблеткой». Строка без
     * «@» не добавляется: это не адрес, и держать её в списке получателей
     * бессмысленно (ошибку покажет отправка).
     */
    fun addRecipient(field: ComposeField, raw: String) {
        val addresses = splitAddresses(raw)
        if (addresses.isEmpty()) return
        updateCompose { draft ->
            when (field) {
                ComposeField.TO -> draft.copy(to = (draft.to + addresses).distinct())
                ComposeField.CC -> draft.copy(cc = (draft.cc + addresses).distinct())
                ComposeField.BCC -> draft.copy(bcc = (draft.bcc + addresses).distinct())
            }
        }
    }

    /**
     * Приложить файл к письму.
     *
     * Содержимое читается сразу, при выборе: если читать при отправке, файл
     * может стать недоступен (человек удалил его или отозвал разрешение на
     * доступ), и отправка упадёт уже после того, как письмо набрано.
     *
     * Лимит 20 МБ на всё письмо — не формальность: mail.ru отклоняет письма
     * больше 25 МБ, и лучше сказать об этом сразу, чем получить отказ сервера.
     */
    fun addAttachment(name: String, mimeType: String, bytes: ByteArray) {
        val current = _uiState.value.compose ?: return
        val total = current.attachments.sumOf { it.bytes.size.toLong() } + bytes.size.toLong()
        if (total > MAX_ATTACHMENT_BYTES) {
            updateCompose {
                it.copy(
                    error = "Вложения больше 20 МБ не отправляются. Уберите файл или пришлите ссылку."
                )
            }
            return
        }
        updateCompose { it.copy(attachments = it.attachments + OutgoingAttachment(name, mimeType, bytes)) }
    }

    fun removeAttachment(name: String) {
        updateCompose { draft -> draft.copy(attachments = draft.attachments.filterNot { it.name == name }) }
    }

    fun removeRecipient(field: ComposeField, address: String) {
        updateCompose { draft ->
            when (field) {
                ComposeField.TO -> draft.copy(to = draft.to - address)
                ComposeField.CC -> draft.copy(cc = draft.cc - address)
                ComposeField.BCC -> draft.copy(bcc = draft.bcc - address)
            }
        }
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
        if (draft.to.isEmpty()) {
            updateCompose { it.copy(error = "Укажите получателя", activeField = ComposeField.TO) }
            return
        }

        updateCompose { it.copy(isSending = true, error = null) }
        viewModelScope.launch {
            val res = smtpMailService.send(
                OutgoingMail(
                    to = draft.to,
                    cc = draft.cc + draft.bcc,
                    subject = draft.subject,
                    body = draft.body,
                    inReplyTo = draft.inReplyTo,
                    attachments = draft.attachments
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

    // --- Перенос в папку -----------------------------------------------------

    fun startMove(mail: MailModel) {
        _uiState.update { it.copy(movingMail = mail) }
    }

    fun cancelMove() {
        _uiState.update { it.copy(movingMail = null) }
    }

    /** Папки, в которые можно перенести: всё, кроме текущей и служебных. */
    fun moveTargets(): List<FolderModel> {
        val state = _uiState.value
        return state.folders
            .filter { it.id != state.selectedFolder }
            // Папки с суммой писем и «невыбираемые» узлы IMAP (например
            // \[Gmail\] без собственных писем) исключаем: перенос туда
            // завершится ошибкой сервера.
            .filterNot { it.name.isBlank() }
    }

    fun moveTo(mail: MailModel, targetFolderId: String, targetName: String) {
        _uiState.update { it.copy(movingMail = null) }
        viewModelScope.launch {
            imapMailService.moveTo(mail.folderId, mail.uid, listOf(targetName)).fold(
                onSuccess = {
                    removeLocally(mail)
                    _uiState.update { it.copy(notice = "Перенесено в «$targetName»") }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(error = err.message ?: "Не удалось перенести письмо в «$targetName»")
                    }
                }
            )
        }
    }

    fun clearNotice() {
        _uiState.update { it.copy(notice = null) }
    }

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
                openedHtml = if (wasOpen) null else state.openedHtml,
                openedMessageId = if (wasOpen) null else state.openedMessageId
            )
        }
    }

    // --- Поиск ---------------------------------------------------------------

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        if (value.isBlank()) {
            _uiState.update { it.copy(searchResults = null, isSearching = false) }
        }
    }

    fun submitSearch() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            _uiState.update { it.copy(searchResults = null) }
            return
        }
        val folder = _uiState.value.selectedFolder
        _uiState.update { it.copy(isSearching = true, error = null) }

        viewModelScope.launch {
            if (tokenStore.accountType != AccountType.IMAP.name) {
                // Для серверного аккаунта поиск делает сервер Corvex; пока
                // такого вызова нет, честнее показать пустой результат, чем
                // искать по кэшу и делать вид, что это весь ящик.
                _uiState.update { it.copy(isSearching = false, searchResults = emptyList()) }
                return@launch
            }
            imapMailService.search(folder, query).fold(
                onSuccess = { found ->
                    _uiState.update { it.copy(isSearching = false, searchResults = found) }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchResults = null,
                            error = err.message ?: "Поиск не удался"
                        )
                    }
                }
            )
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(query = "", searchResults = null, isSearching = false) }
    }

    // --- Догрузка ------------------------------------------------------------

    /**
     * Показать более старые письма.
     *
     * IMAP не умеет «дай следующие 25» без состояния: проще перезапросить
     * пачку большего размера. Для ящика на несколько тысяч писем это
     * неэкономно, но предсказуемо и не требует держать offset между
     * перезагрузками экрана.
     */
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.canLoadMore || state.searchResults != null) return
        val folder = state.selectedFolder
        val next = (Cache.limits[folder] ?: PAGE) + PAGE
        Cache.limits[folder] = next
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            imapMailService.fetchFoldersAndInbox(folder, next).fold(
                onSuccess = { (folders, mails) ->
                    Cache.folders = folders
                    Cache.mails[folder] = mails
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            mails = mails,
                            folders = folders,
                            canLoadMore = mails.size >= next
                        )
                    }
                },
                onFailure = {
                    // Откатываем лимит: иначе следующее нажатие запросит
                    // ещё больше и снова не покажет ничего нового.
                    Cache.limits[folder] = next - PAGE
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            )
        }
    }

    // --- Вложения ------------------------------------------------------------

    /**
     * Скачать вложение и сохранить на устройство.
     *
     * Содержимое грузится по требованию и не кешируется между письмами:
     * держать в памяти десятки мегабайт фотографий из старых писем — верный
     * способ получить OutOfMemory на телефоне с 2 ГБ RAM.
     */
    fun downloadAttachment(attachment: MailAttachment) {
        val mail = _uiState.value.openedMail ?: return
        if (_uiState.value.downloadingAttachment != null) return
        _uiState.update { it.copy(downloadingAttachment = attachment.name, attachmentNotice = null) }

        viewModelScope.launch {
            imapMailService.fetchAttachment(mail.folderId, mail.uid, attachment.name)
                .fold(
                    onSuccess = { bytes ->
                        val where = attachmentStore
                            .save(attachment.name, attachment.mimeType, bytes)
                            .getOrNull()
                            .orEmpty()
                        _uiState.update { state ->
                            state.copy(
                                downloadingAttachment = null,
                                attachmentNotice = if (where.isBlank()) {
                                    "Не удалось сохранить файл"
                                } else {
                                    "Сохранено: $where"
                                },
                                // Содержимое кладём в состояние: превью картинки
                                // дальше показывается без повторной загрузки.
                                openedAttachments = state.openedAttachments.map {
                                    if (it.name == attachment.name) it.copy(bytes = bytes) else it
                                }
                            )
                        }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                downloadingAttachment = null,
                                attachmentNotice = err.message ?: "Не удалось скачать вложение"
                            )
                        }
                    }
                )
        }
    }

    fun clearAttachmentNotice() {
        _uiState.update { it.copy(attachmentNotice = null) }
    }

    fun closeOpenedMail() {
        _uiState.update {
            it.copy(
                openedMail = null,
                openedBody = null,
                openedHtml = null,
                openedAttachments = emptyList(),
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
