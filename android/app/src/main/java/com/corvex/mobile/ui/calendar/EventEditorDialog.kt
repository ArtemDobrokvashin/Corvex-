package com.corvex.mobile.ui.calendar

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.corvex.mobile.data.calendar.CalendarTime
import com.corvex.mobile.domain.calendar.CalendarDraft
import com.corvex.mobile.domain.calendar.CalendarEvent
import java.time.LocalDate
import java.time.LocalTime

/**
 * Редактор события: одна форма, без отдельных экранов.
 *
 * Время вводится текстом `ЧЧ:ММ`, а не колесом: колесо на телефоне занимает
 * пол-экрана, а диктуется время всё равно цифрами. Разбор — чистые функции
 * ниже, их проверяет юнит-тест.
 */
@Composable
fun EventEditorDialog(
    event: CalendarEvent,
    onSave: (CalendarDraft) -> Unit,
    onDelete: (CalendarEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val isNew = event.isNew
    var title by remember { mutableStateOf(if (isNew) "" else event.title) }
    var description by remember { mutableStateOf(event.description) }
    var location by remember { mutableStateOf(event.location) }
    var dateText by remember { mutableStateOf(event.date.toString()) }
    var startText by remember { mutableStateOf(event.startTime.format(HH_MM)) }
    var endText by remember { mutableStateOf(event.endTime.format(HH_MM)) }
    var allDay by remember { mutableStateOf(event.allDay) }
    var recurrence by remember { mutableStateOf(event.recurrence) }
    var priority by remember { mutableStateOf(event.priority) }
    var reminderText by remember {
        mutableStateOf(event.reminderMinutes?.toString().orEmpty())
    }
    // Приглашённые переживают правку: сервер заменяет событие целиком, и без
    // переноса списка адресов они бы молча пропали при смене одного названия.
    var attendees by remember { mutableStateOf(event.attendees) }
    var confirmDelete by remember { mutableStateOf(false) }

    val parsedDate = parseDate(dateText)
    val parsedStart = parseTime(startText)
    val parsedEnd = parseTime(endText)

    // Проверки собраны в одном месте: кнопка «Сохранить» неактивна, пока
    // форма заведомо не пройдёт серверную валидацию (пустое название, конец
    // раньше начала, кривая дата). Это дешевле, чем ловить 400.
    val problem = when {
        title.isBlank() -> "Введите название"
        parsedDate == null -> "Дата в формате ГГГГ-ММ-ДД"
        !allDay && parsedStart == null -> "Начало в формате ЧЧ:ММ"
        !allDay && parsedEnd == null -> "Конец в формате ЧЧ:ММ"
        !allDay && parsedEnd!! <= parsedStart!! -> "Конец должен быть позже начала"
        reminderText.isNotBlank() && reminderMinutes(reminderText) == null -> "Напоминание — минуты, до 10080"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Новое событие" else "Событие") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("Дата, ГГГГ-ММ-ДД") },
                    singleLine = true,
                    isError = parsedDate == null,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Начало") },
                        singleLine = true,
                        enabled = !allDay,
                        isError = !allDay && parsedStart == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("Конец") },
                        singleLine = true,
                        enabled = !allDay,
                        isError = !allDay && parsedEnd == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Весь день")
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Повтор:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    // Короткий список правил из серверного CalendarRecurrence:
                    // произвольное правило сервер всё равно отклонит.
                    DropdownPicker(
                        options = RECURRENCES,
                        selected = recurrence,
                        label = { recurrenceLabelOf(it) },
                        onSelect = { recurrence = it }
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Приоритет:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                    DropdownPicker(
                        options = PRIORITIES,
                        selected = priority,
                        label = { priorityLabelOf(it) },
                        onSelect = { priority = it }
                    )
                }
                OutlinedTextField(
                    value = reminderText,
                    onValueChange = { reminderText = it },
                    label = { Text("Напоминание, минут (пусто — нет)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Место") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (problem != null) {
                    Text(
                        text = problem,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = {
                    onSave(
                        CalendarDraft(
                            title = title.trim(),
                            description = description,
                            location = location,
                            date = parsedDate!!,
                            startTime = if (allDay) LocalTime.MIDNIGHT else parsedStart!!,
                            endTime = if (allDay) LocalTime.of(23, 59) else parsedEnd!!,
                            allDay = allDay,
                            // Зона — устройства: человек вводит местное время.
                            timeZone = CalendarTime.deviceZone().id,
                            recurrence = recurrence,
                            reminderMinutes = reminderMinutes(reminderText),
                            priority = priority,
                            attendees = attendees
                        )
                    )
                }
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (!isNew) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Удалить") }
                }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить событие?") },
            text = { Text("«${event.title}» пропадёт из календаря.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(event)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun DropdownPicker(
    options: List<String>,
    selected: String,
    label: (String) -> String,
    onSelect: (String) -> Unit
) {
    // Не `open`: это ключевое слово Kotlin (модификатор), и объявить такую
    // переменную нельзя — компилятор скажет «Expecting a variable name».
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

internal val HH_MM = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

/** «09:30» → LocalTime; «930», «9:30», «09.30» тоже понимаются. */
internal fun parseTime(raw: String): LocalTime? {
    val cleaned = raw.trim().replace('.', ':').replace(" ", ":")
    if (cleaned.isEmpty()) return null
    val parts = cleaned.split(':')
    return runCatching {
        when (parts.size) {
            1 -> {
                val digits = parts[0]
                if (digits.length > 2) null
                else LocalTime.of(digits.toInt().coerceIn(0, 23), 0)
            }
            2 -> LocalTime.of(parts[0].toInt(), parts[1].toInt())
            else -> null
        }
    }.getOrNull()
}

/** «2026-10-19» → LocalDate. Другие форматы не принимаем: их легко перепутать. */
internal fun parseDate(raw: String): LocalDate? =
    runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

/** Пусто — напоминания нет; вне 0..10080 сервер отклонит. */
internal fun reminderMinutes(raw: String): Int? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val minutes = value.toIntOrNull() ?: return null
    return if (minutes in 0..10_080) minutes else null
}

internal fun recurrenceLabelOf(rule: String): String = when (rule) {
    "daily" -> "каждый день"
    "weekdays" -> "по будням"
    "weekly" -> "каждую неделю"
    "monthly" -> "каждый месяц"
    else -> "нет"
}

internal fun priorityLabelOf(priority: String): String = when (priority) {
    "high" -> "высокий"
    "low" -> "низкий"
    else -> "обычный"
}

private val RECURRENCES = listOf("none", "daily", "weekdays", "weekly", "monthly")
private val PRIORITIES = listOf("medium", "high", "low")
