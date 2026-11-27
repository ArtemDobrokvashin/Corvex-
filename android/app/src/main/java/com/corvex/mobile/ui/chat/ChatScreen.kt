package com.corvex.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.domain.chat.ChatMessage
import com.corvex.mobile.domain.chat.ChatRoom
import com.corvex.mobile.ui.common.MailFormatting
import com.corvex.mobile.ui.theme.CorvexColors

/**
 * Чат: список комнат и лента выбранной комнаты на одном экране.
 *
 * Отдельный экран для комнаты не нужен: комнат немного, а лишний переход
 * усложняет возврат. Выбор комнаты — это состояние ViewModel, а не маршрут.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Ошибку показываем полосой снизу, а не диалогом: диалог перекрывает поле
    // ввода, и человек теряет набранный текст.
    LaunchedEffect(state.error) {
        val message = state.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
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
                            if (state.openedRoom != null) viewModel.closeRoom() else onBack()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.openedRoom?.title ?: "Чат",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.openedRoom != null) {
                            Text(
                                // Честно про транспорт: без WebSocket сообщения
                                // приходят только при открытии комнаты.
                                text = if (state.isLive) "На связи" else "Без живого соединения",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.isLive) CorvexColors.Success else CorvexColors.Faint
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val room = state.openedRoom
            if (room == null) {
                RoomList(
                    rooms = state.rooms,
                    isLoading = state.isLoadingRooms,
                    onRefresh = { viewModel.refreshRooms() },
                    onOpen = viewModel::openRoom
                )
            } else {
                MessageList(
                    state = state,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::send
                )
            }
        }
    }

}

@Composable
private fun RoomList(
    rooms: List<ChatRoom>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpen: (ChatRoom) -> Unit
) {
    if (isLoading && rooms.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CorvexColors.Accent)
        }
        return
    }
    if (rooms.isEmpty()) {
        EmptyChats(onRefresh)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(rooms, key = { it.id }) { room ->
            RoomRow(room = room, onClick = { onOpen(room) })
            HorizontalDivider(color = CorvexColors.LineSubtle)
        }
    }
}

@Composable
private fun RoomRow(room: ChatRoom, onClick: () -> Unit) {
    Surface(
        color = CorvexColors.Surface,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(CorvexColors.AccentSoft, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = room.title.take(1).uppercase(),
                    color = CorvexColors.Accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = room.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (room.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    // Пустая комната тоже говорит о себе: без этого строка
                    // выглядит как сломанная загрузка.
                    text = room.lastMessageText ?: "Нет сообщений",
                    style = MaterialTheme.typography.bodySmall,
                    color = CorvexColors.Faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = MailFormatting.listTimestamp(room.lastMessageAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint
                )
                if (room.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Badge(
                        containerColor = CorvexColors.Accent,
                        contentColor = Color.White
                    ) {
                        Text(
                            text = if (room.unreadCount > 99) "99+" else room.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChats(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Chat,
            contentDescription = null,
            tint = CorvexColors.LineControl,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("Чатов пока нет", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Комнаты создаются в веб-интерфейсе Corvex.",
            style = MaterialTheme.typography.bodySmall,
            color = CorvexColors.Faint
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRefresh) { Text("Обновить") }
    }
}

@Composable
private fun MessageList(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val listState = rememberLazyListState()

    // Держим последнее сообщение в поле зрения: после отправки и при открытии
    // комнаты человек смотрит вниз, а не ищет новое сообщение прокруткой.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.isLoadingMessages) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = CorvexColors.Accent
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
        Composer(
            draft = state.draft,
            enabled = state.openedRoom?.isReadOnly == false,
            onDraftChange = onDraftChange,
            onSend = onSend
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val bubbleColor = if (message.isMine) CorvexColors.Accent else CorvexColors.Surface
    val textColor = if (message.isMine) Color.White else CorvexColors.Ink

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = bubbleColor,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isMine) 16.dp else 4.dp,
                        bottomEnd = if (message.isMine) 4.dp else 16.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (message.isDeleted) "Сообщение удалено" else message.text,
                color = if (message.isDeleted) CorvexColors.Faint else textColor,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = MailFormatting.listTimestamp(message.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.isMine) Color.White.copy(alpha = 0.7f) else CorvexColors.Faint,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(color = CorvexColors.Chrome, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение") },
                maxLines = 4,
                enabled = enabled,
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
            }
        }
    }
}
