package com.corvex.mobile.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.repository.DriveRepository
import com.corvex.mobile.domain.drive.DriveNode
import com.corvex.mobile.domain.drive.DriveUsage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FilesUiState(
    /** Путь от корня к открытой папке: нужен для «вверх» и заголовка. */
    val path: List<DriveNode> = emptyList(),
    val nodes: List<DriveNode> = emptyList(),
    val usage: DriveUsage? = null,
    val isLoading: Boolean = false,
    val isUploading: Boolean = false,
    val query: String = "",
    val trashMode: Boolean = false,
    val notice: String? = null,
    val error: String? = null
) {
    val currentFolder: DriveNode? get() = path.lastOrNull()
}

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val driveRepository: DriveRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    init {
        reload()
        loadUsage()
    }

    fun reload() {
        val state = _uiState.value
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            driveRepository.list(
                parentId = state.currentFolder?.id,
                view = if (state.trashMode) "trash" else "files",
                query = state.query
            )
                .onSuccess { nodes ->
                    _uiState.update { it.copy(isLoading = false, nodes = nodes) }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, error = err.message ?: "Не удалось открыть папку")
                    }
                }
        }
    }

    fun loadUsage() {
        viewModelScope.launch {
            driveRepository.usage().onSuccess { usage ->
                _uiState.update { it.copy(usage = usage) }
            }
        }
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        // Поиск серверный: фильтр по уже загруженной папке не нашёл бы файл
        // в другой папке, а «поиск» обычно означает именно это.
        reload()
    }

    fun open(node: DriveNode) {
        if (node.isFolder && !node.trashed) {
            _uiState.update {
                it.copy(path = it.path + node, nodes = emptyList(), query = "")
            }
            reload()
        }
    }

    /** «Вверх»: на один уровень, не сбрасывая поиск в корень. */
    fun goUp() {
        val state = _uiState.value
        if (state.path.isEmpty()) return
        _uiState.update { it.copy(path = it.path.dropLast(1), nodes = emptyList(), query = "") }
        reload()
    }

    fun toggleTrash() {
        _uiState.update {
            it.copy(trashMode = !it.trashMode, path = emptyList(), query = "", nodes = emptyList())
        }
        reload()
    }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            driveRepository.createFolder(_uiState.value.currentFolder?.id, clean)
                .onSuccess {
                    _uiState.update { s -> s.copy(notice = "Папка создана") }
                    reload()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(error = err.message ?: "Не удалось создать папку") }
                }
        }
    }

    fun upload(uri: android.net.Uri) {
        if (_uiState.value.isUploading) return
        _uiState.update { it.copy(isUploading = true, error = null) }
        viewModelScope.launch {
            driveRepository.upload(_uiState.value.currentFolder?.id, uri)
                .onSuccess { node ->
                    _uiState.update { it.copy(isUploading = false, notice = "Загружено: ${node.name}") }
                    reload()
                    loadUsage()
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isUploading = false, error = err.message ?: "Файл не загружен")
                    }
                }
        }
    }

    fun rename(node: DriveNode, name: String) {
        viewModelScope.launch {
            driveRepository.rename(node.id, name)
                .onSuccess { reload() }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Не удалось переименовать") } }
        }
    }

    fun move(node: DriveNode, parentId: String?) {
        viewModelScope.launch {
            driveRepository.move(node.id, parentId)
                .onSuccess { reload() }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Не удалось переместить") } }
        }
    }

    fun trash(node: DriveNode) {
        viewModelScope.launch {
            driveRepository.trash(node.id)
                .onSuccess {
                    _uiState.update { s -> s.copy(notice = "«${node.name}» в корзине") }
                    reload()
                    loadUsage()
                }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Не удалось удалить") } }
        }
    }

    fun restore(node: DriveNode) {
        viewModelScope.launch {
            driveRepository.restore(node.id)
                .onSuccess { reload() }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Не удалось восстановить") } }
        }
    }

    /** Окончательное удаление доступно только для корзины. */
    fun purge(node: DriveNode) {
        viewModelScope.launch {
            driveRepository.purge(node.id)
                .onSuccess { reload(); loadUsage() }
                .onFailure { err -> _uiState.update { it.copy(error = err.message ?: "Не удалось удалить") } }
        }
    }

    fun download(node: DriveNode) {
        _uiState.update { it.copy(notice = "Скачивание: ${node.name}", error = null) }
        viewModelScope.launch {
            driveRepository.download(node)
                .onSuccess { where -> _uiState.update { it.copy(notice = "Сохранено: $where") } }
                .onFailure { err ->
                    _uiState.update { it.copy(error = err.message ?: "Не удалось скачать файл") }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(notice = null, error = null) }
    }
}
