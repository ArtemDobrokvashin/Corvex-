package com.corvex.mobile.ui.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.domain.drive.DriveNode
import com.corvex.mobile.ui.common.MailFormatting
import com.corvex.mobile.ui.theme.CorvexColors
import java.util.Locale

/**
 * Личный диск: папки, файлы, корзина.
 *
 * Один экран вместо двух: переход в папку — это состояние ViewModel (путь),
 * а не маршрут. Так работает и кнопка «назад» на телефоне, и «вверх» в шапке.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onBack: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DriveNode?>(null) }
    var operationFor by remember { mutableStateOf<DriveNode?>(null) }

    // Итог действий («Сохранено: …», «Файл не загружен») показываем полосой:
    // диалог перекрывал бы список, из которого человек только что выбрал файл.
    LaunchedEffect(state.notice, state.error) {
        val message = state.error ?: state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessages()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.upload(uri)
    }

    Scaffold(
        containerColor = CorvexColors.Canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CorvexColors.Chrome,
                    titleContentColor = CorvexColors.Ink,
                    navigationIconContentColor = CorvexColors.Ink
                ),
                navigationIcon = {
                    IconButton(
                        onClick = {
                            // Назад: сначала на уровень выше по папкам, и только
                            // потом из раздела. Иначе кнопка выкидывает из диска.
                            if (state.trashMode) viewModel.toggleTrash()
                            else if (state.path.isNotEmpty()) viewModel.goUp()
                            else onBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = when {
                                state.trashMode -> "Корзина"
                                state.currentFolder != null -> state.currentFolder!!.name
                                else -> "Файлы"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.usage?.let { usage ->
                            Text(
                                text = "${bytesOf(usage.usedBytes)} из ${bytesOf(usage.quotaBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CorvexColors.Faint
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.trashMode) {
                        Icon(Icons.Filled.Upload, contentDescription = "Загрузить файл")
                    }
                    IconButton(onClick = { showNewFolder = true }, enabled = !state.trashMode) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = "Новая папка")
                    }
                    IconButton(onClick = viewModel::toggleTrash) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = if (state.trashMode) "К файлам" else "Корзина",
                            tint = if (state.trashMode) CorvexColors.Accent else CorvexColors.Ink
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Поиск по имени") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            if (state.usage != null) {
                LinearProgressIndicator(
                    progress = { state.usage!!.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    color = if (state.usage!!.fraction > 0.9f) CorvexColors.Danger else CorvexColors.Accent
                )
            }
            if (state.isUploading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CorvexColors.Accent)
            }
            if (state.isLoading && state.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CorvexColors.Accent)
                }
            } else if (state.nodes.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (state.trashMode) Icons.Filled.Delete else Icons.Filled.Folder,
                        contentDescription = null,
                        tint = CorvexColors.LineControl,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when {
                            state.query.isNotBlank() -> "Ничего не найдено"
                            state.trashMode -> "Корзина пуста"
                            else -> "Папка пуста"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Загрузить файл можно кнопкой сверху.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CorvexColors.Faint,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.nodes, key = { it.id }) { node ->
                        NodeRow(
                            node = node,
                            trashMode = state.trashMode,
                            onClick = {
                                if (node.isFolder && !state.trashMode) viewModel.open(node)
                                else if (!node.isFolder && !state.trashMode) viewModel.download(node)
                            },
                            onMenu = { operationFor = node }
                        )
                        HorizontalDivider(color = CorvexColors.LineSubtle)
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        TextPromptDialog(
            title = "Новая папка",
            label = "Название",
            initial = "",
            confirmLabel = "Создать",
            onConfirm = {
                showNewFolder = false
                viewModel.createFolder(it)
            },
            onDismiss = { showNewFolder = false }
        )
    }

    renameTarget?.let { node ->
        TextPromptDialog(
            title = "Переименовать",
            label = "Новое имя",
            initial = node.name,
            confirmLabel = "Сохранить",
            onConfirm = {
                renameTarget = null
                viewModel.rename(node, it)
            },
            onDismiss = { renameTarget = null }
        )
    }

    operationFor?.let { node ->
        AlertDialog(
            onDismissRequest = { operationFor = null },
            title = { Text(node.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (node.isFolder && !state.trashMode) {
                        DialogItem("Открыть") {
                            operationFor = null
                            viewModel.open(node)
                        }
                    }
                    if (!state.trashMode) {
                        DialogItem("Скачать") {
                            operationFor = null
                            viewModel.download(node)
                        }
                        DialogItem("Переименовать") {
                            operationFor = null
                            renameTarget = node
                        }
                        DialogItem("В корзину") {
                            operationFor = null
                            viewModel.trash(node)
                        }
                    } else {
                        DialogItem("Восстановить") {
                            operationFor = null
                            viewModel.restore(node)
                        }
                        DialogItem("Удалить навсегда") {
                            operationFor = null
                            viewModel.purge(node)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { operationFor = null }) { Text("Закрыть") }
            }
        )
    }
}

@Composable
private fun NodeRow(node: DriveNode, trashMode: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (node.isFolder) CorvexColors.AccentSoft else CorvexColors.LineSubtle,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (node.isFolder) Icons.Filled.Folder else Icons.Filled.Description,
                contentDescription = null,
                tint = if (node.isFolder) CorvexColors.Accent else CorvexColors.Muted
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (!node.isFolder) append(bytesOf(node.sizeBytes))
                    if (!node.isFolder && node.updatedAt > 0) append(" · ")
                    if (node.updatedAt > 0) append(MailFormatting.listTimestamp(node.updatedAt))
                },
                style = MaterialTheme.typography.labelSmall,
                color = CorvexColors.Faint
            )
        }
        if (trashMode) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Restore, contentDescription = "Действия", tint = CorvexColors.Muted)
            }
        } else {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Действия")
            }
        }
    }
}

@Composable
private fun DialogItem(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = CorvexColors.Ink
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/** Размер файла по-русски: «12,4 МБ». Единицы — как в проводнике Windows. */
internal fun bytesOf(bytes: Long): String {
    if (bytes <= 0) return "0 Б"
    val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} ${units[0]}"
    else String.format(Locale("ru"), "%.1f %s", value, units[index])
}
