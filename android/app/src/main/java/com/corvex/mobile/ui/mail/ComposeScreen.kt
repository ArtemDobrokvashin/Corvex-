package com.corvex.mobile.ui.mail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.corvex.mobile.ui.home.ComposeKind
import com.corvex.mobile.ui.home.ComposeState
import com.corvex.mobile.ui.theme.CorvexColors

/**
 * Экран написания: новое письмо, ответ, ответ всем, пересылка — один экран,
 * разное только исходное состояние.
 *
 * Черновик живёт в ViewModel, а не в `remember`: при повороте экрана или
 * сворачивании приложения написанный текст не должен исчезать.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    state: ComposeState,
    onChange: ((ComposeState) -> ComposeState) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit
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
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть")
                    }
                },
                title = {
                    Text(
                        text = when (state.kind) {
                            ComposeKind.NEW -> "Новое письмо"
                            ComposeKind.REPLY -> "Ответ"
                            ComposeKind.REPLY_ALL -> "Ответ всем"
                            ComposeKind.FORWARD -> "Переслать"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    Button(
                        onClick = onSend,
                        enabled = !state.isSending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CorvexColors.Accent,
                            contentColor = Color.White
                        )
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.padding(horizontal = 3.dp))
                            Text("Отправить")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = state.to,
                onValueChange = { value -> onChange { it.copy(to = value, error = null) } },
                label = { Text("Кому") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.kind == ComposeKind.REPLY_ALL || state.cc.isNotEmpty()) {
                OutlinedTextField(
                    value = state.cc,
                    onValueChange = { value -> onChange { it.copy(cc = value) } },
                    label = { Text("Копия") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = state.subject,
                onValueChange = { value -> onChange { it.copy(subject = value) } },
                label = { Text("Тема") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.body,
                onValueChange = { value -> onChange { it.copy(body = value) } },
                label = { Text("Текст письма") },
                minLines = 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                textStyle = MaterialTheme.typography.bodyMedium
            )

            if (state.error != null) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = CorvexColors.Danger
                    )
                }
            }

            Text(
                // Честно про ограничение: HTML-письма уходят простым текстом,
                // и жирный шрифт/картинки, набранные в вебе, здесь не сохранятся.
                text = "Письмо уходит простым текстом. Вложения пока не поддерживаются.",
                style = MaterialTheme.typography.labelSmall,
                color = CorvexColors.Faint
            )
        }
    }
}
