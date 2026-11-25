package com.corvex.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.imap.ImapMailService
import com.corvex.mobile.data.repository.MailRepository
import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.AccountType
import com.corvex.mobile.domain.mail.Flag
import com.corvex.mobile.domain.mail.FolderModel
import com.corvex.mobile.domain.mail.MailModel
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
    val openedBodyError: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val imapMailService: ImapMailService,
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
        _uiState.update { it.copy(openedMail = mail, openedBody = null, openedBodyError = null) }
        viewModelScope.launch {
            val res = imapMailService.fetchBody(mail.folderId, mail.uid)
            res.onSuccess { body ->
                _uiState.update { it.copy(openedBody = body.bodyText) }
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

    fun closeOpenedMail() {
        _uiState.update { it.copy(openedMail = null, openedBody = null, openedBodyError = null) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        tokenStore.clearAuth()
        onLoggedOut()
    }
}
