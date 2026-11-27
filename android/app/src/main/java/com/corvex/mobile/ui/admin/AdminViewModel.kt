package com.corvex.mobile.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.network.api.AdminAnalyticsDto
import com.corvex.mobile.data.network.api.AdminGroupDto
import com.corvex.mobile.data.network.api.AdminHealthDto
import com.corvex.mobile.data.network.api.AdminUserDto
import com.corvex.mobile.data.network.api.AuditEntryDto
import com.corvex.mobile.data.repository.AdminRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AdminTab(val title: String) {
    USERS("Пользователи"),
    HEALTH("Сервер"),
    AUDIT("Аудит"),
    ANALYTICS("Аналитика")
}

data class AdminUiState(
    val tab: AdminTab = AdminTab.USERS,
    val users: List<AdminUserDto> = emptyList(),
    val query: String = "",
    val page: Int = 0,
    val totalPages: Int = 0,
    val totalElements: Long = 0,
    val groups: List<AdminGroupDto> = emptyList(),
    val health: AdminHealthDto? = null,
    val analytics: AdminAnalyticsDto? = null,
    val audit: List<AuditEntryDto> = emptyList(),
    val selectedUser: AdminUserDto? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val adminRepository: AdminRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminUiState())
    val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

    init {
        loadUsers()
        loadGroups()
    }

    fun selectTab(tab: AdminTab) {
        _uiState.update { it.copy(tab = tab) }
        when (tab) {
            AdminTab.USERS -> if (_uiState.value.users.isEmpty()) loadUsers()
            AdminTab.HEALTH -> loadHealth()
            AdminTab.AUDIT -> loadAudit()
            AdminTab.ANALYTICS -> loadAnalytics()
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        loadUsers(page = 0)
    }

    private fun loadUsers(page: Int = _uiState.value.page) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            adminRepository.users(_uiState.value.query, page)
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            users = result.users,
                            page = result.page,
                            totalPages = result.totalPages,
                            totalElements = result.totalElements
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, error = err.message ?: "Пользователи не загрузились") }
                }
        }
    }

    fun nextPage() {
        val state = _uiState.value
        if (state.page + 1 < state.totalPages) loadUsers(state.page + 1)
    }

    fun previousPage() {
        val state = _uiState.value
        if (state.page > 0) loadUsers(state.page - 1)
    }

    private fun loadGroups() {
        viewModelScope.launch {
            adminRepository.groups().onSuccess { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
    }

    fun loadHealth() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            adminRepository.health()
                .onSuccess { health ->
                    _uiState.update { it.copy(isLoading = false, health = health) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, error = err.message ?: "Health недоступен") }
                }
        }
    }

    fun loadAudit() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            adminRepository.audit()
                .onSuccess { entries ->
                    _uiState.update { it.copy(isLoading = false, audit = entries) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, error = err.message ?: "Аудит недоступен") }
                }
        }
    }

    fun loadAnalytics() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            adminRepository.analytics()
                .onSuccess { analytics ->
                    _uiState.update { it.copy(isLoading = false, analytics = analytics) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(isLoading = false, error = err.message ?: "Аналитика недоступна") }
                }
        }
    }

    fun openUser(user: AdminUserDto) {
        _uiState.update { it.copy(selectedUser = user) }
    }

    fun closeUser() {
        _uiState.update { it.copy(selectedUser = null) }
    }

    fun setQuota(user: AdminUserDto, quotaBytes: Long) {
        viewModelScope.launch {
            adminRepository.setQuota(user.id, quotaBytes)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            users = state.users.map { if (it.id == updated.id) updated else it },
                            selectedUser = updated,
                            notice = "Квота обновлена"
                        )
                    }
                }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Квота не изменена") } }
        }
    }

    fun setGroups(user: AdminUserDto, groups: List<String>) {
        viewModelScope.launch {
            adminRepository.setGroups(user.id, groups)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            users = state.users.map { if (it.id == updated.id) updated else it },
                            selectedUser = updated,
                            notice = "Группы обновлены"
                        )
                    }
                }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Группы не изменены") } }
        }
    }

    /**
     * Блокировка и разблокировка.
     *
     * Сервер умеет только блокировать (`POST /block`); снять блокировку —
     * это `PATCH status=ACTIVE`. Так и делаем, иначе кнопка «разблокировать»
     * на телефоне была бы мёртвой.
     */
    fun toggleBlock(user: AdminUserDto) {
        val target = if (user.status == "BLOCKED") "ACTIVE" else "BLOCKED"
        viewModelScope.launch {
            adminRepository.setStatus(user, target)
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            users = state.users.map { if (it.id == updated.id) updated else it },
                            selectedUser = updated,
                            notice = if (target == "BLOCKED") "Пользователь заблокирован" else "Блокировка снята"
                        )
                    }
                }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Статус не изменён") } }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, notice = null) }
    }
}
