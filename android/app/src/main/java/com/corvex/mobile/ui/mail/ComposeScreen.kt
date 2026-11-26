package com.corvex.mobile.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.corvex.mobile.ui.theme.CorvexColors

/**
 * Экран написания: новое письмо, ответ, ответ всем, пересылка.
 *
 * Устроен как в Gmail и mail.ru на Android: сверху только «назад» и
 * «отправить», ниже — строки «Кому / Копия / Тема» без рамок, получатели
 * «таблетками», тело занимает всё оставшееся место, панель форматирования —
 * снизу, под рукой.
 *
 * Раньше это были три `OutlinedTextField` в рамке и кнопка в шапке: адреса
 * приходилось вводить строкой через запятую, «Копии» не было вообще, тело
 * занимало фиксированные 320 dp и обрезало письмо.
 *
 * Черновик живёт в ViewModel, а не в `remember`: поворот экрана не должен
 * стирать написанное.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    state: ComposeState,
    onChange: ((ComposeState) -> ComposeState) -> Unit,
    onAddRecipient: (ComposeField, String) -> Unit,
    onRemoveRecipient: (ComposeField, String) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit
) {
    var toInput by remember { mutableStateOf("") }
    var ccInput by remember { mutableStateOf("") }
    var bccInput by remember { mutableStateOf("") }
    val bodyFocus = remember { FocusRequester() }
    val toFocus = remember { FocusRequester() }

    // Новое письмо — курсор в «Кому», ответ — сразу в тело: в ответе адрес уже
    // известен, и заставлять человека тыкать в поле, которое он не меняет, —
    // лишний шаг.
    LaunchedEffect(Unit) {
        runCatching {
            if (state.kind == ComposeKind.NEW) toFocus.requestFocus() else bodyFocus.requestFocus()
        }
    }

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
                            ComposeKind.FORWARD -> "Пересылка"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = !state.isSending,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(40.dp)
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = "Отправить",
                                modifier = Modifier.size(20.dp)
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
            RecipientRow(
                label = "Кому",
                addresses = state.to,
                input = toInput,
                onInputChange = { toInput = it },
                onCommit = {
                    onAddRecipient(ComposeField.TO, toInput)
                    toInput = ""
                },
                onRemove = { onRemoveRecipient(ComposeField.TO, it) },
                focusRequester = toFocus
            )

            val showCcRow = state.showCc || state.cc.isNotEmpty()
            val showBccRow = state.showBcc || state.bcc.isNotEmpty()

            if (showCcRow) {
                HorizontalDivider(color = CorvexColors.LineSubtle)
                RecipientRow(
                    label = "Копия",
                    addresses = state.cc,
                    input = ccInput,
                    onInputChange = { ccInput = it },
                    onCommit = {
                        onAddRecipient(ComposeField.CC, ccInput)
                        ccInput = ""
                    },
                    onRemove = { onRemoveRecipient(ComposeField.CC, it) }
                )
            }
            if (showBccRow) {
                HorizontalDivider(color = CorvexColors.LineSubtle)
                RecipientRow(
                    label = "Скрытая",
                    addresses = state.bcc,
                    input = bccInput,
                    onInputChange = { bccInput = it },
                    onCommit = {
                        onAddRecipient(ComposeField.BCC, bccInput)
                        bccInput = ""
                    },
                    onRemove = { onRemoveRecipient(ComposeField.BCC, it) }
                )
            }

            if (!showCcRow || !showBccRow) {
                Row(
                    modifier = Modifier.padding(start = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!showCcRow) {
                        TextButton(
                            onClick = { onChange { it.copy(showCc = true) } },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("Копия", style = MaterialTheme.typography.labelMedium) }
                    }
                    if (!showBccRow) {
                        TextButton(
                            onClick = { onChange { it.copy(showBcc = true) } },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("Скрытая", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }

            HorizontalDivider(color = CorvexColors.LineSubtle)

            // Тема — без рамки: рамка вокруг одной строки визуально делает её
            // «полем формы», а не заголовком письма.
            BasicTextField(
                value = state.subject,
                onValueChange = { value -> onChange { it.copy(subject = value) } },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CorvexColors.Ink
                ),
                cursorBrush = SolidColor(CorvexColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (state.subject.isEmpty()) {
                        Text(
                            text = "Тема",
                            style = MaterialTheme.typography.bodyLarge,
                            color = CorvexColors.Faint
                        )
                    }
                    inner()
                }
            )

            HorizontalDivider(color = CorvexColors.LineSubtle)

            // Тело занимает всё оставшееся место и прокручивается внутри себя —
            // письмо на десять абзацев больше не обрезается на 320 dp.
            BasicTextField(
                value = state.body,
                onValueChange = { value -> onChange { it.copy(body = value) } },
                textStyle = TextStyle(fontSize = 15.sp, color = CorvexColors.InkSoft, lineHeight = 22.sp),
                cursorBrush = SolidColor(CorvexColors.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .focusRequester(bodyFocus),
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (state.body.isEmpty() && state.kind == ComposeKind.NEW) {
                            Text(
                                text = "Напишите письмо…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CorvexColors.Faint
                            )
                        }
                        inner()
                    }
                }
            )

            if (state.error != null) {
                Surface(color = CorvexColors.BadgeCriticalBg) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = CorvexColors.Danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }

            ComposeToolbar()
        }
    }
}

/**
 * Строка получателей: «Кому» подписью, адреса «таблетками», поле ввода справа.
 *
 * Ввод коммитится по запятой, точке с запятой, пробелу и Enter — так же, как в
 * Gmail. Иначе пришлось бы закончить ввод и ткнуть «добавить», а такой кнопки
 * в почтовых клиентах нет.
 */
@Composable
private fun RecipientRow(
    label: String,
    addresses: List<String>,
    input: String,
    onInputChange: (String) -> Unit,
    onCommit: () -> Unit,
    onRemove: (String) -> Unit,
    focusRequester: FocusRequester? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = CorvexColors.Faint,
                modifier = Modifier.width(70.dp)
            )
            Box(modifier = Modifier.weight(1f)) {
                Column {
                    if (addresses.isNotEmpty()) {
                        // Chips идут строкой с переносом: при пяти адресах они
                        // не уезжают за край экрана.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            addresses.forEach { address ->
                                SuggestionChip(
                                    onClick = { onRemove(address) },
                                    label = {
                                        Text(
                                            text = address,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Убрать $address",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = CorvexColors.AccentSoft,
                                        labelColor = CorvexColors.AccentDark,
                                        iconContentColor = CorvexColors.AccentDark
                                    ),
                                    border = null,
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { value ->
                            // Запятая и точка с запятой завершают адрес — иначе
                            // пришлось бы искать отдельную кнопку.
                            if (value.any { it == ',' || it == ';' }) {
                                onInputChange(value.replace(",", " ").replace(";", " "))
                                onCommit()
                            } else {
                                onInputChange(value)
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 15.sp, color = CorvexColors.Ink),
                        cursorBrush = SolidColor(CorvexColors.Accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = {
                            onCommit()
                            // Enter после адреса переносит ввод в следующее поле.
                            onInputChange("")
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (addresses.isEmpty()) 0.dp else 6.dp)
                            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    text = if (addresses.isEmpty()) "Адрес почты" else "Ещё адрес",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CorvexColors.Faint
                                )
                            }
                            inner()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Панель под письмом.
 *
 * Кнопки форматирования пока не меняют текст — в письме уходит простой текст,
 * и притворяться, что жирный шрифт сохранится, было бы обманом. Кнопки
 * вложений тоже нет: она выключена с подписью, а не спрятана, чтобы не
 * создавать впечатление, что вложения поддерживаются.
 */
@Composable
private fun ComposeToolbar() {
    HorizontalDivider(color = CorvexColors.LineSubtle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CorvexColors.Surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(
            Icons.Filled.FormatBold to "Жирный",
            Icons.Filled.FormatItalic to "Курсив",
            Icons.Filled.FormatUnderlined to "Подчёркнутый",
            Icons.Filled.AttachFile to "Вложение"
        ).forEach { (icon, description) ->
            IconButton(onClick = { /* форматирование и вложения — отдельная задача */ }, enabled = false) {
                Icon(
                    icon,
                    contentDescription = description,
                    modifier = Modifier.size(20.dp),
                    tint = CorvexColors.Faint
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "Простой текст",
            style = MaterialTheme.typography.labelSmall,
            color = CorvexColors.Faint,
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}
