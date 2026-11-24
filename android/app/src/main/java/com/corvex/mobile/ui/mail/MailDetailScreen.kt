package com.corvex.mobile.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.corvex.mobile.domain.mail.MailModel
import com.corvex.mobile.ui.common.MailFormatting
import com.corvex.mobile.ui.theme.CorvexColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fullDateFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale("ru"))

/**
 * Экран чтения письма. Показывает то, что уже есть в модели письма: тело в
 * текстовом виде (HTML не рендерится — это следующий шаг) и заголовки.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailDetailScreen(
    mail: MailModel,
    onBack: () -> Unit
) {
    Scaffold(
        containerColor = CorvexColors.Surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CorvexColors.Chrome,
                    titleContentColor = CorvexColors.Ink
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = { Text("Письмо", style = MaterialTheme.typography.titleMedium) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = mail.subject.ifBlank { "(без темы)" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = CorvexColors.Ink
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = mail.sender)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = MailFormatting.displayName(mail.sender),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = CorvexColors.Ink
                    )
                    Text(
                        text = addressOf(mail.sender),
                        style = MaterialTheme.typography.labelSmall,
                        color = CorvexColors.Faint
                    )
                }
                Text(
                    text = fullDate(mail.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint
                )
            }

            HorizontalDivider(color = CorvexColors.Line, modifier = Modifier.padding(vertical = 14.dp))

            val body = mail.bodyText?.takeIf { it.isNotBlank() } ?: mail.snippet
            Text(
                text = body.ifBlank { "(пустое письмо)" },
                style = MaterialTheme.typography.bodyMedium,
                color = CorvexColors.InkSoft
            )
        }
    }
}

@Composable
private fun Avatar(name: String?) {
    val initial = MailFormatting.initial(name)
    if (initial.isEmpty()) return
    Box(
        modifier = Modifier
            .size(38.dp)
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

private fun addressOf(sender: String?): String {
    val raw = sender?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    return if (raw.contains('<')) raw.substringAfter('<').substringBefore('>') else raw
}

private fun fullDate(receivedAtMillis: Long): String {
    if (receivedAtMillis <= 0L) return ""
    val moment = Instant.ofEpochMilli(receivedAtMillis).atZone(ZoneId.systemDefault())
    return fullDateFormatter.format(moment)
}
