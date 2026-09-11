package com.lumenpearson.lessons.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.SchoolDay
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Days per page-row; the school week is shown Monday through Sunday. */
private const val DaysInWeek = 7

/**
 * One page of the pager.
 *
 * @property day `null` when the cached timetable does not reach this date, which
 *   is a different thing from "no lessons" and is rendered differently.
 */
data class WeekDayUi(
    val date: LocalDate,
    val day: SchoolDay?,
    val isToday: Boolean,
)

/**
 * @property weekOffset 0 for the current week, negative for past weeks.
 * @property initialPage the page to open on: today, or Monday for other weeks.
 */
data class WeekUiState(
    val isLoading: Boolean = true,
    val weekOffset: Int = 0,
    val weekStart: LocalDate = LocalDate.now(),
    val days: List<WeekDayUi> = emptyList(),
    val initialPage: Int = 0,
    val showTeacher: Boolean = true,
) {
    /** Last day of the shown week; the top bar renders the range. */
    val weekEnd: LocalDate get() = weekStart.plusDays((DaysInWeek - 1).toLong())
}

/**
 * Week view state holder.
 *
 * It keeps a week *offset* rather than a date so that leaving the app open past
 * midnight still shows the right week: the offset is applied to whatever "today"
 * is at the moment the state is built.
 */
class WeekViewModel(
    timetableRepository: TimetableRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val weekOffset = MutableStateFlow(0)

    /**
     * Coarse clock. The week view only cares about the date, so a minute is
     * plenty and costs nothing next to the home screen's 30-second tick.
     */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(DATE_TICK_MILLIS)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<WeekUiState> = combine(
        timetableRepository.timetable,
        today,
        weekOffset,
        settingsRepository.settings,
    ) { timetable, today, offset, settings ->
        val weekStart = today
            .plusWeeks(offset.toLong())
            .with(DayOfWeek.MONDAY)
        val days = (0 until DaysInWeek).map { index ->
            val date = weekStart.plusDays(index.toLong())
            WeekDayUi(
                date = date,
                day = timetable?.day(date),
                isToday = date == today,
            )
        }
        WeekUiState(
            isLoading = false,
            weekOffset = offset,
            weekStart = weekStart,
            days = days,
            initialPage = days.indexOfFirst { it.isToday }.takeIf { it >= 0 } ?: 0,
            showTeacher = settings.showTeacher,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = WeekUiState(),
    )

    /** Steps one week back; the pager reopens on Monday. */
    fun showPreviousWeek() {
        weekOffset.value -= 1
    }

    /** Steps one week forward, within whatever the cache actually holds. */
    fun showNextWeek() {
        weekOffset.value += 1
    }

    /** Returns to the current week — the top bar's title doubles as this action. */
    fun showCurrentWeek() {
        weekOffset.value = 0
    }

    companion object {
        private const val DATE_TICK_MILLIS = 60_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WeekViewModel(
                    timetableRepository = Graph.container.timetableRepository,
                    settingsRepository = Graph.container.settingsRepository,
                )
            }
        }
    }
}
