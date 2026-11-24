package com.corvex.mobile.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.imap.ImapMailService
import com.corvex.mobile.data.repository.MailRepository
import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.AccountType
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
    val openedMail: MailModel? = null
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
        refresh()
    }

    fun selectFolder(folderId: String) {
        _uiState.update { it.copy(selectedFolder = folderId) }
        refresh()
    }

    fun refresh() {
        val isImap = tokenStore.accountType == AccountType.IMAP.name
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            if (isImap) {
                val res = imapMailService.fetchFoldersAndInbox(_uiState.value.selectedFolder)
                res.onSuccess { (folders, mails) ->
                    _uiState.update { it.copy(isLoading = false, folders = folders, mails = mails) }
                }.onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, error = err.message ?: "Ошибка загрузки писем") }
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

    /** Открытое письмо. Полное тело появится по мере готовности экрана чтения. */
    fun open(mail: MailModel) {
        _uiState.update { it.copy(openedMail = mail) }
    }

    fun closeOpenedMail() {
        _uiState.update { it.copy(openedMail = null) }
    }

    fun logout(onLoggedOut: () -> Unit) {
        tokenStore.clearAuth()
        onLoggedOut()
    }
}
