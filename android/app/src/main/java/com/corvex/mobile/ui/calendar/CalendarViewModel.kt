package com.corvex.mobile.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.corvex.mobile.data.calendar.CalendarTime
import com.corvex.mobile.data.repository.CalendarRepository
import com.corvex.mobile.domain.calendar.CalendarDraft
import com.corvex.mobile.domain.calendar.CalendarEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import javax.inject.Inject

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    /** События месяца, уже развёрнутые по повторениям, по датам. */
    val eventsByDate: Map<LocalDate, List<CalendarEvent>> = emptyMap(),
    val editing: CalendarEvent? = null,
    val isEditorOpen: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val notice: String? = null
) {
    val selectedEvents: List<CalendarEvent> get() = eventsByDate[selectedDate].orEmpty()
}

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        val month = _uiState.value.month
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            // Запрашиваем с запасом в месяц назад и вперёд: события в начале
            // сетки приходят из предыдущего месяца.
            val (from, to) = CalendarTime.window(month)
            calendarRepository.events(from, to)
                .onSuccess { events ->
                    _uiState.update {
                        it.copy(isLoading = false, eventsByDate = expand(events, from, to))
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(isLoading = false, error = err.message ?: "События не загрузились")
                    }
                }
        }
    }

    /**
     * Разворот повторений по датам.
     *
     * Сервер отдаёт повторяющееся событие одной записью и ожидает, что клиент
     * сам развернёт вхождения (так же делает веб). Делаем это один раз при
     * загрузке, а не при отрисовке: иначе на каждый кадр приходился бы цикл
     * по дням.
     */
    private fun expand(
        events: List<CalendarEvent>,
        from: LocalDate,
        to: LocalDate
    ): Map<LocalDate, List<CalendarEvent>> {
        val result = mutableMapOf<LocalDate, MutableList<CalendarEvent>>()
        events.forEach { event ->
            CalendarTime.occurrences(event, from, to).forEach { (date, _) ->
                result.getOrPut(date) { mutableListOf() }.add(event)
            }
        }
        result.values.forEach { list ->
            list.sortWith(compareBy({ it.allDay.not() }, { it.startTime }))
        }
        return result
    }

    fun selectDate(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
    }

    fun nextMonth() {
        changeMonth(_uiState.value.month.plusMonths(1))
    }

    fun previousMonth() {
        changeMonth(_uiState.value.month.minusMonths(1))
    }

    private fun changeMonth(month: YearMonth) {
        _uiState.update { it.copy(month = month, selectedDate = month.atDay(1)) }
        reload()
    }

    fun today() {
        val now = LocalDate.now()
        _uiState.update { it.copy(month = YearMonth.from(now), selectedDate = now) }
        reload()
    }

    /** Новое событие на выбранный день, по умолчанию — ближайший круглый час. */
    fun createForSelectedDate() {
        val now = LocalTime.now()
        val start = LocalTime.of((now.hour + 1).coerceAtMost(23), 0)
        val date = _uiState.value.selectedDate
        val draft = CalendarDraft(
            title = "",
            date = date,
            startTime = start,
            endTime = start.plusHours(1).let { if (it.isBefore(start)) LocalTime.of(23, 59) else it },
            timeZone = CalendarTime.deviceZone().id
        )
        _uiState.update { it.copy(editing = draft.toPreviewEvent(), isEditorOpen = true) }
    }

    fun editEvent(event: CalendarEvent) {
        _uiState.update { it.copy(editing = event, isEditorOpen = true) }
    }

    fun closeEditor() {
        _uiState.update { it.copy(isEditorOpen = false, editing = null) }
    }

    fun save(draft: CalendarDraft) {
        val editing = _uiState.value.editing
        viewModelScope.launch {
            val result = if (editing?.isNew == false) {
                calendarRepository.update(editing, draft)
            } else {
                calendarRepository.create(draft)
            }
            result
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            isEditorOpen = false,
                            editing = null,
                            notice = "Событие сохранено",
                            selectedDate = draft.date,
                            month = YearMonth.from(draft.date)
                        )
                    }
                    reload()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(error = err.message ?: "Не удалось сохранить событие") }
                }
        }
    }

    fun delete(event: CalendarEvent) {
        viewModelScope.launch {
            calendarRepository.delete(event)
                .onSuccess {
                    _uiState.update { state ->
                        state.copy(isEditorOpen = false, editing = null, notice = "Событие удалено")
                    }
                    reload()
                }
                .onFailure { err ->
                    _uiState.update { it.copy(error = err.message ?: "Не удалось удалить событие") }
                }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(error = null, notice = null) }
    }
}

/** Черновик как событие — для предзаполнения редактора. Id нет: это новое. */
internal fun CalendarDraft.toPreviewEvent(): CalendarEvent = CalendarEvent(
    id = null,
    title = title,
    description = description,
    location = location,
    startsAt = CalendarTime.toInstant(date, startTime, CalendarTime.deviceZone()),
    endsAt = CalendarTime.toInstant(date, endTime, CalendarTime.deviceZone()),
    allDay = allDay,
    timeZone = timeZone,
    priority = priority,
    busyStatus = "busy",
    recurrence = recurrence,
    reminderMinutes = reminderMinutes,
    attendees = emptyList(),
    revision = 0,
    date = date,
    startTime = startTime,
    endTime = endTime
)
