package com.corvex.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.domain.mail.MailModel
import com.corvex.mobile.ui.common.MailFormatting
import com.corvex.mobile.ui.mail.ComposeScreen
import com.corvex.mobile.ui.mail.MailDetailScreen
import com.corvex.mobile.ui.theme.CorvexColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onLoggedOut: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    // Экран написания перекрывает всё: черновик не должен теряться за списком.
    state.compose?.let { draft ->
        ComposeScreen(
            state = draft,
            onChange = viewModel::updateCompose,
            onSend = viewModel::sendCompose,
            onClose = viewModel::closeCompose
        )
        return
    }

    val opened = state.openedMail
    if (opened != null) {
        MailDetailScreen(
            mail = opened,
            bodyText = state.openedBody,
            bodyError = state.openedBodyError,
            onBack = viewModel::closeOpenedMail,
            onRefresh = { viewModel.open(opened) },
            onReply = { viewModel.composeReply(opened, all = false) },
            onReplyAll = { viewModel.composeReply(opened, all = true) },
            onForward = { viewModel.composeForward(opened) },
            onArchive = { viewModel.archive(opened) },
            onDelete = { viewModel.delete(opened) }
        )
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
        }
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::composeNew,
                containerColor = CorvexColors.Accent,
                contentColor = Color.White
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Написать письмо")
            }
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

            if (state.mails.isEmpty() && !state.isLoading && state.error == null) {
                EmptyState()
            }

            if (state.mails.isEmpty() && !state.isLoading) {
                // Пусто — но кнопка «Написать» должна быть на месте: без неё
                // из пустого ящика некуда деться.
                Button(
                    onClick = viewModel::composeNew,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CorvexColors.Accent,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                    Text("Написать письмо")
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.mails, key = { it.id }) { mail ->
                    MailRow(
                        mail = mail,
                        onClick = { viewModel.open(mail) },
                        onToggleStar = { viewModel.toggleStar(mail) },
                        onToggleRead = { viewModel.toggleRead(mail) }
                    )
                    HorizontalDivider(color = CorvexColors.LineSubtle, thickness = 1.dp)
                }
            }
        }
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
