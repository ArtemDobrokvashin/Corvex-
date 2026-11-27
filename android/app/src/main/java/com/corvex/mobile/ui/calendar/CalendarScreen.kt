package com.corvex.mobile.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Today
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.corvex.mobile.domain.calendar.CalendarEvent
import com.corvex.mobile.ui.theme.CorvexColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthTitle = DateTimeFormatter.ofPattern("LLLL yyyy", Locale("ru"))
private val dayTitle = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale("ru"))
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))

/**
 * Календарь: сетка месяца, список дня и редактор события.
 *
 * Сетка рисуется Column×Row, а не LazyVerticalGrid: ячеек ровно 42, ленивость
 * не нужна, а вложенный вертикальный скролл (сетка + список дня) в Compose
 * работает через weight и капризничает.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
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
                title = {
                    Text(
                        text = state.month.atDay(1).format(monthTitle)
                            .replaceFirstChar { it.titlecase(Locale("ru")) },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::previousMonth) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Предыдущий месяц")
                    }
                    IconButton(onClick = viewModel::today) {
                        Icon(Icons.Filled.Today, contentDescription = "Сегодня")
                    }
                    IconButton(onClick = viewModel::nextMonth) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Следующий месяц")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = viewModel::createForSelectedDate,
                containerColor = CorvexColors.Accent,
                contentColor = Color.White
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Новое событие")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CorvexColors.Accent)
            }
            MonthGrid(
                month = state.month,
                selected = state.selectedDate,
                eventsByDate = state.eventsByDate,
                onSelect = viewModel::selectDate
            )
            HorizontalDivider(color = CorvexColors.Line)
            Text(
                text = state.selectedDate.format(dayTitle)
                    .replaceFirstChar { it.uppercase(Locale("ru")) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleSmall,
                color = CorvexColors.Muted
            )
            if (state.selectedEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "На этот день событий нет",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CorvexColors.Faint
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(state.selectedEvents, key = { it.id ?: it.startsAt + it.title }) { event ->
                        EventRow(event) { viewModel.editEvent(event) }
                        HorizontalDivider(color = CorvexColors.LineSubtle)
                    }
                }
            }
        }
    }

    if (state.isEditorOpen && state.editing != null) {
        EventEditorDialog(
            event = state.editing!!,
            onSave = viewModel::save,
            onDelete = { viewModel.delete(it) },
            onDismiss = viewModel::closeEditor
        )
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    eventsByDate: Map<LocalDate, List<CalendarEvent>>,
    onSelect: (LocalDate) -> Unit
) {
    // Понедельник — первый день: в России неделя начинается с него, и
    // «Вс» в начале строки читалось бы как ошибка.
    val first = month.atDay(1)
    val shift = first.dayOfWeek.value - 1
    val start = first.minusDays(shift.toLong())
    val today = LocalDate.now()
    val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        repeat(6) { week ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { dayIndex ->
                    val date = start.plusDays((week * 7 + dayIndex).toLong())
                    DayCell(
                        date = date,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected,
                        hasEvents = eventsByDate[date]?.isNotEmpty() == true,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(date) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    hasEvents: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .background(
                color = when {
                    isSelected -> CorvexColors.Accent
                    isToday -> CorvexColors.AccentSoft
                    else -> Color.Transparent
                },
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    isSelected -> Color.White
                    !inMonth -> CorvexColors.LineControl
                    else -> CorvexColors.Ink
                }
            )
            if (hasEvents) {
                Box(
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(4.dp)
                        .background(
                            if (isSelected) Color.White else CorvexColors.Accent,
                            CircleShape
                        )
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: CalendarEvent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.width(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (event.allDay) {
                Text("весь", style = MaterialTheme.typography.labelSmall, color = CorvexColors.Faint)
                Text("день", style = MaterialTheme.typography.labelSmall, color = CorvexColors.Faint)
            } else {
                Text(
                    event.startTime.format(timeFormat),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    event.endTime.format(timeFormat),
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint
                )
            }
        }
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .background(
                    when (event.priority) {
                        "high" -> CorvexColors.Danger
                        "low" -> CorvexColors.Faint
                        else -> CorvexColors.Accent
                    },
                    RoundedCornerShape(2.dp)
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                if (event.location.isNotBlank()) append(event.location)
                if (event.recurrenceLabel.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(event.recurrenceLabel)
                }
                if (event.reminderMinutes != null) {
                    if (isNotEmpty()) append(" · ")
                    append("напоминание")
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = CorvexColors.Faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(Icons.Filled.Schedule, contentDescription = null, tint = CorvexColors.LineControl)
    }
}
