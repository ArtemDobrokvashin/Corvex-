package com.corvex.mobile.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.domain.mail.MailModel
import com.corvex.mobile.ui.common.MailFormatting
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

    val opened = state.openedMail
    if (opened != null) {
        MailDetailScreen(mail = opened, onBack = viewModel::closeOpenedMail)
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
                                text = MailFormatting.letters(state.mails.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = CorvexColors.Faint
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !state.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
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

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.mails, key = { it.id }) { mail ->
                    MailRow(mail = mail, onClick = { viewModel.open(mail) })
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
private fun MailRow(mail: MailModel, onClick: () -> Unit) {
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
            if (preview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = CorvexColors.Faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
