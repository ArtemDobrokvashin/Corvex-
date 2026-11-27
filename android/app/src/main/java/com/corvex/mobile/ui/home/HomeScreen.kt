package com.corvex.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.domain.mail.FolderModel
import com.corvex.mobile.domain.mail.MailModel
import com.corvex.mobile.ui.common.MailFormatting
import com.corvex.mobile.ui.mail.ComposeScreen
import com.corvex.mobile.ui.mail.MailDetailScreen
import com.corvex.mobile.ui.theme.CorvexColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLoggedOut: () -> Unit,
    onOpenChat: () -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    onOpenAdmin: () -> Unit = {},
    /**
     * Внешний ящик (IMAP): чат, календарь, файлы и админка — это сервер
     * Corvex, а не mail.ru. Раздел, который заведомо не заработает, лучше
     * не показывать, чем показать «не удалось загрузить».
     */
    serverFeatures: Boolean = true,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Экран написания перекрывает всё: черновик не должен теряться за списком.
    state.compose?.let { draft ->
        ComposeScreen(
            state = draft,
            onChange = viewModel::updateCompose,
            onAddRecipient = viewModel::addRecipient,
            onRemoveRecipient = viewModel::removeRecipient,
            onRemoveAttachment = viewModel::removeAttachment,
            onAttach = viewModel::addAttachment,
            onSend = viewModel::sendCompose,
            onClose = viewModel::closeCompose
        )
        return
    }

    val opened = state.openedMail
    if (opened != null) {
        // Результат сохранения вложения: Toast, а не Snackbar — экран письма
        // не содержит Scaffold со SnackbarHost, а перестраивать его ради
        // одного сообщения не стоит.
        val context = LocalContext.current
        LaunchedEffect(state.attachmentNotice) {
            val notice = state.attachmentNotice ?: return@LaunchedEffect
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            viewModel.clearAttachmentNotice()
        }
        LaunchedEffect(state.notice) {
            val notice = state.notice ?: return@LaunchedEffect
            Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
            viewModel.clearNotice()
        }
        MailDetailScreen(
            mail = opened,
            bodyText = state.openedBody,
            bodyHtml = state.openedHtml,
            bodyError = state.openedBodyError,
            onBack = viewModel::closeOpenedMail,
            onRefresh = { viewModel.open(opened) },
            onReply = { viewModel.composeReply(opened, all = false) },
            onReplyAll = { viewModel.composeReply(opened, all = true) },
            onForward = { viewModel.composeForward(opened) },
            onArchive = { viewModel.archive(opened) },
            onMove = { viewModel.startMove(opened) },
            onDelete = { viewModel.delete(opened) },
            attachments = state.openedAttachments,
            downloadingAttachment = state.downloadingAttachment,
            onDownloadAttachment = viewModel::downloadAttachment
        )
        state.movingMail?.let { mail ->
            MoveToFolderDialog(
                folders = viewModel.moveTargets(),
                onPick = { folder -> viewModel.moveTo(mail, folder.id, folder.name) },
                onDismiss = viewModel::cancelMove
            )
        }
        return
    }

    Scaffold(
        containerColor = CorvexColors.Canvas,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CorvexColors.Chrome,
                    titleContentColor = CorvexColors.Ink
                ),
                title = {
                    Column {
                        Text(
                            text = MailFormatting.displayName(state.accountLabel),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.mails.isNotEmpty()) {
                            Text(
                                // Не «писем в ящике», а «загружено»: клиент тянет
                                // последние 25, и цифра без пояснения обманывает.
                                text = "Загружено: ${MailFormatting.letters(state.mails.size)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = CorvexColors.Faint
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refresh() },
                        enabled = !state.isLoading
                    ) {
                        // Пока идёт обновление, иконка крутится: иначе нажатие
                        // выглядит как «ничего не произошло».
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CorvexColors.Accent
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (serverFeatures) {
                                DropdownMenuItem(
                                    text = { Text("Чат") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenChat()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Календарь") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenCalendar()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Файлы") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenFiles()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Администрирование") },
                                    onClick = {
                                        menuOpen = false
                                        onOpenAdmin()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Обновить") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.refresh()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Выйти") },
                                onClick = {
                                    menuOpen = false
                                    viewModel.logout(onLoggedOut)
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            // В Gmail это не круг с пером, а вытянутая таблетка «Написать»:
            // подпись объясняет действие, а форма совпадает с остальными
            // кнопками приложения.
            ExtendedFloatingActionButton(
                onClick = viewModel::composeNew,
                containerColor = CorvexColors.Accent,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                text = { Text("Написать", style = MaterialTheme.typography.labelMedium) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.folders.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CorvexColors.Chrome),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.folders) { folder ->
                        FilterChip(
                            selected = folder.id == state.selectedFolder,
                            onClick = { viewModel.selectFolder(folder.id) },
                            label = { Text(folder.name, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CorvexColors.AccentSoft,
                                selectedLabelColor = CorvexColors.AccentDark,
                                containerColor = CorvexColors.Surface,
                                labelColor = CorvexColors.Muted
                            )
                        )
                    }
                }
                HorizontalDivider(color = CorvexColors.Line)
            }

            // Тонкая полоса только когда показать нечего. При обновлении списка
            // поверх уже загруженного она не нужна: список не исчезает.
            if (state.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CorvexColors.Accent,
                    trackColor = CorvexColors.AccentSoft
                )
            }

            state.error?.let { err ->
                Surface(
                    color = CorvexColors.BadgeCriticalBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = CorvexColors.BadgeCriticalText,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            // В пустом ящике «Написать» уже есть — это FAB в Scaffold. Вторая
            // кнопка здесь дублировала бы его и съедала место у EmptyState.
            if (state.mails.isEmpty() && !state.isLoading && state.error == null) {
                EmptyState()
            }

            // Поле поиска живёт над списком, а не в отдельном экране: в почте
            // ищут «на месте», не теряя папку и контекст.
            SearchField(
                query = state.query,
                isSearching = state.isSearching,
                onQueryChange = viewModel::onQueryChange,
                onSubmit = viewModel::submitSearch,
                onClear = viewModel::clearSearch
            )

            val visible = state.searchResults ?: state.mails

            if (state.searchResults != null && !state.isSearching && visible.isEmpty()) {
                Text(
                    text = "По запросу «${state.query}» ничего не найдено",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CorvexColors.Faint,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.id }) { mail ->
                    MailRow(
                        mail = mail,
                        onClick = { viewModel.open(mail) },
                        onToggleStar = { viewModel.toggleStar(mail) },
                        onToggleRead = { viewModel.toggleRead(mail) }
                    )
                    HorizontalDivider(color = CorvexColors.LineSubtle, thickness = 1.dp)
                }

                if (state.searchResults == null && state.canLoadMore && state.mails.isNotEmpty()) {
                    item(key = "load_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.isLoadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = CorvexColors.Accent
                                )
                            } else {
                                OutlinedButton(onClick = viewModel::loadMore) {
                                    Text("Показать ещё")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Поле поиска.
 *
 * Отправка по Enter и по кнопке-лупе: на телефоне клавиатура часто убирает
 * нижнюю часть экрана, и без кнопки пришлось бы искать «ввод» вслепую.
 */
@Composable
private fun SearchField(
    query: String,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CorvexColors.Chrome)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            placeholder = { Text("Поиск по почте", style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    tint = CorvexColors.Faint,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                when {
                    isSearching -> CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = CorvexColors.Accent
                    )
                    query.isNotEmpty() -> IconButton(onClick = onClear) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Очистить поиск",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    else -> IconButton(onClick = onSubmit) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Найти",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            // Таблетка как в Gmail: полностью скруглённая, серый фон, без
            // рамки. Прямоугольное поле с рамкой читалось как форма ввода.
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CorvexColors.Canvas,
                unfocusedContainerColor = CorvexColors.Canvas,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Строка списка как в вебе: точка непрочитанного, имя отправителя, тема, превью,
 * время справа. Плотность выше веб-таблицы, потому что палец толще курсора.
 */
@Composable
private fun MailRow(
    mail: MailModel,
    onClick: () -> Unit,
    onToggleStar: () -> Unit,
    onToggleRead: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (mail.isRead) CorvexColors.Surface else CorvexColors.BadgeInfoBg.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 10.dp)
                .size(8.dp)
                .background(
                    if (mail.isRead) CorvexColors.LineControl else CorvexColors.Accent,
                    CircleShape
                )
        )
        Avatar(name = mail.sender)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = MailFormatting.displayName(mail.sender),
                    style = MaterialTheme.typography.labelMedium,
                    color = CorvexColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = MailFormatting.listTimestamp(mail.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = mail.subject.ifBlank { "(без темы)" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (mail.isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = CorvexColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val preview = MailFormatting.snippet(mail.snippet, null, limit = 90)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = preview.ifEmpty { "Без текста" },
                style = MaterialTheme.typography.bodySmall,
                color = CorvexColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Действия строки — как в вебе: звёздочка и прочитано/непрочитано.
        // Размер кнопок 32 dp: меньше — палец промахивается, больше — съедает тему.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleStar, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (mail.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (mail.isStarred) "Убрать звёздочку" else "Пометить звёздочкой",
                    tint = if (mail.isStarred) CorvexColors.Warning else CorvexColors.LineControl,
                    modifier = Modifier.size(17.dp)
                )
            }
            IconButton(onClick = onToggleRead, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (mail.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
                    contentDescription = if (mail.isRead) "Отметить непрочитанным" else "Отметить прочитанным",
                    tint = CorvexColors.Faint,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun Avatar(name: String?) {
    val initial = MailFormatting.initial(name)
    if (initial.isEmpty()) return
    Box(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(34.dp)
            .background(CorvexColors.AccentSoft, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = CorvexColors.AccentDark
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Писем нет",
            style = MaterialTheme.typography.titleMedium,
            color = CorvexColors.InkSoft
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Потяните вниз, чтобы проверить почту ещё раз.",
            style = MaterialTheme.typography.bodySmall,
            color = CorvexColors.Faint
        )
    }
}

/**
 * Выбор папки для переноса.
 *
 * Список папок приходит с сервера, поэтому ничего не зашито: у mail.ru это
 * «Отправленные», «Спам», «Черновики», у Gmail — `[Gmail]/Sent Mail` и так
 * далее. Раньше перенос был только в архив и корзину, потому что имя папки
 * приходилось угадывать; теперь его выбирает человек.
 */
@Composable
private fun MoveToFolderDialog(
    folders: List<FolderModel>,
    onPick: (FolderModel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Перенести в папку") },
        text = {
            if (folders.isEmpty()) {
                Text(
                    text = "Список папок ещё не загружен. Откройте список писем и обновите его.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CorvexColors.Faint
                )
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    folders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(folder) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = CorvexColors.Faint,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = folder.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CorvexColors.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
