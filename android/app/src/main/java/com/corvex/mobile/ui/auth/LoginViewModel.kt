package com.corvex.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.imap.ImapMailService
import com.corvex.mobile.data.repository.AuthRepository
import com.corvex.mobile.data.repository.AuthResult
import com.corvex.mobile.data.security.TokenStore
import com.corvex.mobile.domain.mail.AccountType
import com.corvex.mobile.domain.mail.ImapPreset
import com.corvex.mobile.domain.mail.ImapProviderPresets
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val accountType: AccountType = AccountType.CORVEX,
    val selectedPreset: ImapPreset? = ImapProviderPresets.presets.firstOrNull(),
    val serverUrl: String = "https://",
    val imapHost: String = ImapProviderPresets.presets.first().imapHost,
    val imapPort: String = ImapProviderPresets.presets.first().imapPort.toString(),
    val smtpHost: String = ImapProviderPresets.presets.first().smtpHost,
    val smtpPort: String = ImapProviderPresets.presets.first().smtpPort.toString(),
    val email: String = "",
    val password: String = "",
    val totpCode: String = "",
    val mfaToken: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isAuthenticated: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val imapMailService: ImapMailService,
    private val tokenStore: TokenStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onAccountTypeChange(type: AccountType) = _uiState.update {
        it.copy(accountType = type, error = null)
    }

    fun onPresetSelected(preset: ImapPreset) = _uiState.update {
        it.copy(
            selectedPreset = preset,
            imapHost = preset.imapHost,
            imapPort = preset.imapPort.toString(),
            smtpHost = preset.smtpHost,
            smtpPort = preset.smtpPort.toString(),
            error = null
        )
    }

    fun onCustomImapSelected() = _uiState.update {
        it.copy(selectedPreset = null, imapHost = "", imapPort = "993", smtpHost = "", smtpPort = "465", error = null)
    }

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value, error = null) }
    fun onImapHostChange(value: String) = _uiState.update { it.copy(imapHost = value, error = null) }
    fun onSmtpHostChange(value: String) = _uiState.update { it.copy(smtpHost = value, error = null) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onTotpChange(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() }) {
            _uiState.update { it.copy(totpCode = value, error = null) }
        }
    }

    fun submitLogin() {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.accountType == AccountType.IMAP) {
            if (state.email.isBlank() || state.password.isBlank() || state.imapHost.isBlank()) {
                _uiState.update { it.copy(error = "Заполните адрес почты, пароль приложения и IMAP-сервер") }
                return
            }
            val imapPortInt = state.imapPort.toIntOrNull() ?: 993
            val smtpPortInt = state.smtpPort.toIntOrNull() ?: 465
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, error = null) }
                val res = imapMailService.verifyConnection(
                    host = state.imapHost.trim(),
                    port = imapPortInt,
                    email = state.email.trim(),
                    password = state.password
                )
                res.onSuccess {
                    tokenStore.accountType = AccountType.IMAP.name
                    tokenStore.imapHost = state.imapHost.trim()
                    tokenStore.imapPort = imapPortInt
                    tokenStore.smtpHost = state.smtpHost.trim()
                    tokenStore.smtpPort = smtpPortInt
                    tokenStore.imapEmail = state.email.trim()
                    tokenStore.imapPassword = state.password
                    _uiState.update { it.copy(isLoading = false, isAuthenticated = true) }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Не удалось подключиться: ${err.message ?: "проверьте адрес и пароль приложения"}"
                        )
                    }
                }
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val res = authRepository.login(state.serverUrl, state.email, state.password)) {
                is AuthResult.Authenticated -> _uiState.update { it.copy(isLoading = false, isAuthenticated = true) }
                is AuthResult.MfaRequired -> _uiState.update { it.copy(isLoading = false, mfaToken = res.mfaTicket) }
                is AuthResult.Error -> _uiState.update { it.copy(isLoading = false, error = res.message) }
            }
        }
    }

    fun submitTotp() {
        val state = _uiState.value
        val mfaToken = state.mfaToken ?: return
        if (state.totpCode.length != 6 || state.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val res = authRepository.verifyTotp(mfaToken, state.totpCode)) {
                is AuthResult.Authenticated -> _uiState.update { it.copy(isLoading = false, isAuthenticated = true) }
                is AuthResult.Error -> _uiState.update { it.copy(isLoading = false, error = res.message) }
                is AuthResult.MfaRequired -> Unit
            }
        }
    }
}
