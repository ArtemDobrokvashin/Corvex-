package com.corvex.mobile.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.domain.mail.AccountType
import com.corvex.mobile.domain.mail.ImapProviderPresets

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onAuthenticated: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isAuthenticated) {
        if (state.isAuthenticated) onAuthenticated()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Corvex", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(12.dp))

        if (state.mfaToken == null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.accountType == AccountType.CORVEX,
                    onClick = { viewModel.onAccountTypeChange(AccountType.CORVEX) },
                    label = { Text("Сервер Corvex") }
                )
                FilterChip(
                    selected = state.accountType == AccountType.IMAP,
                    onClick = { viewModel.onAccountTypeChange(AccountType.IMAP) },
                    label = { Text("Обычная почта (IMAP)") }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (state.accountType == AccountType.CORVEX) {
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    label = { Text("Адрес сервера (https://mail.company.ru)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("Логин или почта") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImapProviderPresets.presets.forEach { preset ->
                        FilterChip(
                            selected = state.selectedPreset?.id == preset.id,
                            onClick = { viewModel.onPresetSelected(preset) },
                            label = { Text(preset.title) }
                        )
                    }
                    FilterChip(
                        selected = state.selectedPreset == null,
                        onClick = viewModel::onCustomImapSelected,
                        label = { Text("Свой сервер") }
                    )
                }

                state.selectedPreset?.let { preset ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = preset.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("Адрес почты") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text("Пароль приложения") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.imapHost,
                    onValueChange = viewModel::onImapHostChange,
                    label = { Text("IMAP-сервер (например, imap.mail.ru)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.smtpHost,
                    onValueChange = viewModel::onSmtpHostChange,
                    label = { Text("SMTP-сервер (например, smtp.mail.ru)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = viewModel::submitLogin,
                enabled = !state.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isLoading) "Вход..." else "Войти")
            }
        } else {
            Text("Введите 6-значный код TOTP", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = state.totpCode,
                onValueChange = viewModel::onTotpChange,
                label = { Text("Код из приложения") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = viewModel::submitTotp,
                enabled = state.totpCode.length == 6 && !state.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isLoading) "Проверка..." else "Подтвердить")
            }
        }

        state.error?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}
