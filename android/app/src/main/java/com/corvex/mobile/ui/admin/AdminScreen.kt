package com.corvex.mobile.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.data.network.api.AdminUserDto
import com.corvex.mobile.data.parseInstant
import com.corvex.mobile.ui.common.MailFormatting
import com.corvex.mobile.ui.theme.CorvexColors
import java.util.Locale

private const val GIB = 1024.0 * 1024 * 1024

/**
 * Админка: пользователи, состояние сервера, аудит, аналитика.
 *
 * Сознательно не переносим сюда всё, что есть в веб: настройки сервера,
 * миграции и реестр параметров — это работа за компьютером. На телефоне
 * нужны быстрые действия: найти человека, заблокировать, поправить квоту,
 * посмотреть, живы ли сервисы.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    onBack: () -> Unit,
    viewModel: AdminViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error, state.notice) {
        val message = state.error ?: state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessages()
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Администрирование", style = MaterialTheme.typography.titleMedium) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = CorvexColors.Accent)
            }
            ScrollableTabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = CorvexColors.Chrome,
                contentColor = CorvexColors.Accent,
                edgePadding = 8.dp
            ) {
                AdminTab.entries.forEach { tab ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.title) }
                    )
                }
            }
            when (state.tab) {
                AdminTab.USERS -> UsersTab(state, viewModel)
                AdminTab.HEALTH -> HealthTab(state)
                AdminTab.AUDIT -> AuditTab(state)
                AdminTab.ANALYTICS -> AnalyticsTab(state)
            }
        }
    }

    state.selectedUser?.let { user ->
        UserSheet(user = user, state = state, viewModel = viewModel, onDismiss = viewModel::closeUser)
    }
}

@Composable
private fun UsersTab(state: AdminUiState, viewModel: AdminViewModel) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("Поиск по имени или адресу") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Всего: ${state.totalElements}",
                style = MaterialTheme.typography.labelSmall,
                color = CorvexColors.Faint
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::previousPage, enabled = state.page > 0) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Назад")
            }
            Text(
                "${state.page + 1} / ${state.totalPages.coerceAtLeast(1)}",
                style = MaterialTheme.typography.labelSmall
            )
            IconButton(
                onClick = viewModel::nextPage,
                enabled = state.page + 1 < state.totalPages
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Вперёд")
            }
        }
        if (state.users.isEmpty() && !state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Пользователей не найдено",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CorvexColors.Faint
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.users, key = { it.id }) { user ->
                    UserRow(user) { viewModel.openUser(user) }
                    HorizontalDivider(color = CorvexColors.LineSubtle)
                }
            }
        }
    }
}

@Composable
private fun UserRow(user: AdminUserDto, onClick: () -> Unit) {
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
                .background(CorvexColors.AccentSoft, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = (user.displayName.ifBlank { user.username }).take(1).uppercase(),
                color = CorvexColors.Accent,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = user.displayName.ifBlank { user.username },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = user.email.ifBlank { user.username },
                style = MaterialTheme.typography.labelSmall,
                color = CorvexColors.Faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        StatusBadge(user.status)
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (background, text) = when (status) {
        "ACTIVE" -> CorvexColors.BadgePositiveBg to CorvexColors.BadgePositiveText
        "BLOCKED" -> CorvexColors.BadgeCriticalBg to CorvexColors.BadgeCriticalText
        else -> CorvexColors.BadgeWarningBg to CorvexColors.BadgeWarningText
    }
    Text(
        text = when (status) {
            "ACTIVE" -> "активен"
            "BLOCKED" -> "заблокирован"
            "DISABLED" -> "отключён"
            else -> status.lowercase(Locale("ru"))
        },
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = text
    )
}

@Composable
private fun HealthTab(state: AdminUiState) {
    val health = state.health
    LazyColumn(Modifier.fillMaxSize()) {
        if (health != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Metric("Свободно на диске", gigabytes(health.diskFreeBytes), Modifier.weight(1f))
                    Metric("Очередь почты", health.mailQueueDepth.toString(), Modifier.weight(1f))
                }
                HorizontalDivider(color = CorvexColors.Line)
            }
            items(health.services, key = { it.name }) { service ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (service.status.equals("UP", true)) CorvexColors.Success
                                else CorvexColors.Danger,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(service.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        service.version ?: service.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = CorvexColors.Faint
                    )
                }
                HorizontalDivider(color = CorvexColors.LineSubtle)
            }
        } else if (!state.isLoading) {
            item {
                EmptyNote("Состояние сервера недоступно")
            }
        }
    }
}

@Composable
private fun AuditTab(state: AdminUiState) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(state.audit, key = { it.id }) { entry ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(entry.action, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = listOfNotNull(
                        entry.actorName ?: entry.actorId,
                        entry.entity,
                        MailFormatting.listTimestamp(parseInstant(entry.createdAt))
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint
                )
            }
            HorizontalDivider(color = CorvexColors.LineSubtle)
        }
        if (state.audit.isEmpty() && !state.isLoading) {
            item { EmptyNote("Записей нет или нет права AUDIT_VIEW") }
        }
    }
}

@Composable
private fun AnalyticsTab(state: AdminUiState) {
    val analytics = state.analytics
    LazyColumn(Modifier.fillMaxSize()) {
        if (analytics != null) {
            item {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("Всего", analytics.usersTotal.toString(), Modifier.weight(1f))
                        Metric("Активных", analytics.usersActive.toString(), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("Заблокировано", analytics.usersBlocked.toString(), Modifier.weight(1f))
                        Metric("Отключено", analytics.usersDisabled.toString(), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("Входов за 24 ч", analytics.loginsLast24h?.toString() ?: "—", Modifier.weight(1f))
                        Metric("за 7 дней", analytics.loginsLast7d?.toString() ?: "—", Modifier.weight(1f))
                    }
                }
            }
        } else if (!state.isLoading) {
            item { EmptyNote("Аналитика недоступна или нет прав") }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(CorvexColors.Surface, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = CorvexColors.Faint)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = CorvexColors.Faint)
    }
}

// @OptIn обязателен именно здесь: ModalBottomSheet помечен
// @ExperimentalMaterial3Api, а @OptIn на AdminScreen на вложенную функцию не
// распространяется — компилятор ответит «This material API is experimental».
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserSheet(
    user: AdminUserDto,
    state: AdminUiState,
    viewModel: AdminViewModel,
    onDismiss: () -> Unit
) {
    var quotaText by remember(user.id) {
        mutableStateOf(if (user.quotaBytes > 0) gigabytesNumber(user.quotaBytes) else "")
    }
    val quotaBytes = quotaGigabytes(quotaText)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(user.displayName.ifBlank { user.username }, style = MaterialTheme.typography.titleLarge)
            Text(user.email, style = MaterialTheme.typography.bodyMedium, color = CorvexColors.Faint)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(user.status)
                if (user.totpEnabled) {
                    Text(
                        "2FA",
                        modifier = Modifier
                            .background(CorvexColors.BadgeInfoBg, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = CorvexColors.BadgeInfoText
                    )
                }
            }
            Text(
                text = "Группы: " + user.groups.joinToString(", ").ifBlank { "нет" },
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Занято: ${gigabytes(user.usedBytes ?: 0)} из ${gigabytes(user.quotaBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = CorvexColors.Faint
            )
            if (user.lastLoginAt != null) {
                Text(
                    text = "Последний вход: ${MailFormatting.listTimestamp(parseInstant(user.lastLoginAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint
                )
            }

            OutlinedTextField(
                value = quotaText,
                onValueChange = { quotaText = it },
                label = { Text("Квота, ГБ") },
                singleLine = true,
                isError = quotaText.isNotBlank() && quotaBytes == null,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { quotaBytes?.let { viewModel.setQuota(user, it) } },
                    enabled = quotaBytes != null
                ) { Text("Сохранить квоту") }
                OutlinedButton(onClick = { viewModel.toggleBlock(user) }) {
                    Text(if (user.status == "BLOCKED") "Разблокировать" else "Заблокировать")
                }
            }

            if (state.groups.isNotEmpty()) {
                Text("Группы", style = MaterialTheme.typography.titleSmall)
                state.groups.forEach { group ->
                    val checked = user.groups.contains(group.code)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val next = if (checked) user.groups - group.code else user.groups + group.code
                                viewModel.setGroups(user, next)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Text(group.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/** ГиБ, как в веб-админке: квота задаётся в гигабайтах, а не в байтах. */
internal fun gigabytes(bytes: Long): String =
    if (bytes <= 0) "0 ГБ"
    else String.format(Locale("ru"), "%.1f ГБ", bytes / GIB)

internal fun gigabytesNumber(bytes: Long): String {
    if (bytes <= 0) return "0"
    val value = bytes / GIB
    return if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
}

/** «2,5» или «2.5» гигабайт → байты; null — если ввод не число. */
internal fun quotaGigabytes(raw: String): Long? {
    val normalized = raw.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val value = normalized.toDoubleOrNull() ?: return null
    if (value < 0) return null
    return (value * GIB).toLong()
}
