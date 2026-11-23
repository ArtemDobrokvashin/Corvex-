package com.corvex.mobile.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.repository.AuthRepository
import com.corvex.mobile.data.repository.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val serverUrl: String = "https://",
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
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value, error = null) }
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
