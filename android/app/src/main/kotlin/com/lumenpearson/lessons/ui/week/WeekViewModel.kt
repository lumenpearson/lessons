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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Days per row; the school week is shown Monday through Sunday. */
private const val DaysInWeek = 7

/**
 * How much of the timetable the screen shows at once.
 *
 * Three scales of the same data rather than three screens: the week is what a
 * pupil looks at most, the month answers "when is that trip" without stepping
 * through four weeks, and the day laid out against an hour ruler is the only
 * view in which a forty-minute gap looks like a gap.
 */
enum class ScheduleView {
    WEEK,
    MONTH,
    DAY,
}

/**
 * One date on screen.
 *
 * @property day `null` when the cached timetable does not reach this date, which
 *   is a different thing from "no lessons" and is rendered differently.
 * @property inPeriod false for the days of the neighbouring months that fill out
 *   the corners of a month grid. They are shown, faintly, because a grid that
 *   starts mid-row is harder to read than one that does not.
 */
data class WeekDayUi(
    val date: LocalDate,
    val day: SchoolDay?,
    val isToday: Boolean,
    val inPeriod: Boolean = true,
)

/**
 * @property anchor a date inside the shown period; stepping moves this.
 * @property selected the day whose detail is shown under the week or month.
 * @property days every date the current view draws, in order.
 */
data class ScheduleUiState(
    val isLoading: Boolean = true,
    val view: ScheduleView = ScheduleView.WEEK,
    val today: LocalDate = LocalDate.now(),
    val anchor: LocalDate = today,
    val selected: LocalDate = today,
    val periodStart: LocalDate = today,
    val periodEnd: LocalDate = today,
    val days: List<WeekDayUi> = emptyList(),
    val showTeacher: Boolean = true,
    val nowAt: LocalTime? = null,
) {
    /** The day the detail panel and the hour ruler render. */
    val selectedDay: WeekDayUi? get() = days.firstOrNull { it.date == selected }

    /** Whether "back to today" would do anything. */
    val canReturnToToday: Boolean get() = today !in periodStart..periodEnd || selected != today
}

/**
 * Calendar state holder.
 *
 * It keeps an *anchor date* rather than an offset from today, because the three
 * views step by different amounts and a single offset cannot mean a week here
 * and a month there. Today is re-read on a timer, so leaving the app open past
 * midnight moves the highlight instead of stranding it on yesterday.
 */
class WeekViewModel(
    timetableRepository: TimetableRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val view = MutableStateFlow(ScheduleView.WEEK)

    /** Null until the user moves: the anchor follows today while it is untouched. */
    private val anchor = MutableStateFlow<LocalDate?>(null)
    private val selected = MutableStateFlow<LocalDate?>(null)

    /**
     * Coarse clock, truncated to the minute.
     *
     * One timer rather than two. The date is what most of the state depends on,
     * but the hour ruler draws a "now" line, and a line that only moves at
     * midnight is not a now line. A minute is as fine as this screen gets: the
     * state object is rebuilt when it ticks, so a shorter period would be
     * recomposition bought with nothing.
     */
    private val now: Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES))
            delay(CLOCK_TICK_MILLIS)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<ScheduleUiState> = combine(
        timetableRepository.timetable,
        now,
        view,
        combine(anchor, selected) { anchor, selected -> anchor to selected },
        settingsRepository.settings,
    ) { timetable, now, view, focus, settings ->
        val today = now.toLocalDate()
        val (storedAnchor, storedSelection) = focus
        val anchorDate = storedAnchor ?: today
        val selectedDate = storedSelection ?: today

        val (start, end) = view.periodOf(anchorDate)
        val dates = view.datesOf(start, end)

        ScheduleUiState(
            isLoading = false,
            view = view,
            today = today,
            anchor = anchorDate,
            // A selection outside the shown period would leave the detail panel
            // rendering a day the grid does not contain; stepping the period
            // therefore drags the selection with it.
            selected = selectedDate.takeIf { it in start..end } ?: start,
            periodStart = start,
            periodEnd = end,
            days = dates.map { date ->
                WeekDayUi(
                    date = date,
                    day = timetable?.day(date),
                    isToday = date == today,
                    inPeriod = view != ScheduleView.MONTH || date.month == anchorDate.month,
                )
            },
            showTeacher = settings.showTeacher,
            // Only for the day whose ruler could show it; every other day's
            // timeline has no "now" on it and drawing one would be a lie.
            nowAt = now.toLocalTime().takeIf { selectedDate == today },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ScheduleUiState(),
    )

    /** Switches scale, keeping the day in focus rather than jumping to today. */
    fun setView(next: ScheduleView) {
        val focus = selected.value ?: anchor.value ?: LocalDate.now()
        anchor.value = focus
        selected.value = focus
        view.value = next
    }

    /** Steps back one week, month or day, whichever the current view shows. */
    fun showPrevious() = step(-1)

    /** Steps forward by the same amount. */
    fun showNext() = step(1)

    /** Returns to today, in whatever view is open. */
    fun showToday() {
        anchor.value = null
        selected.value = null
    }

    /** Puts a date in focus without changing the period. */
    fun select(date: LocalDate) {
        selected.value = date
    }

    private fun step(direction: Long) {
        val from = anchor.value ?: LocalDate.now()
        val moved = when (view.value) {
            ScheduleView.WEEK -> from.plusWeeks(direction)
            ScheduleView.MONTH -> from.plusMonths(direction)
            ScheduleView.DAY -> from.plusDays(direction)
        }
        anchor.value = moved
        // In the day view the anchor *is* the selection; in the other two the
        // period moved out from under it, so it lands on the new period's start.
        selected.value = moved
    }

    companion object {
        private const val CLOCK_TICK_MILLIS = 30_000L
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

/** First and last date the view draws for an anchor inside it. */
private fun ScheduleView.periodOf(anchor: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
    ScheduleView.WEEK -> {
        val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        start to start.plusDays((DaysInWeek - 1).toLong())
    }

    // The whole grid, not the whole month: a month that starts on a Thursday
    // needs the three days before it to fill its first row, and a cell that is
    // blank is a cell nobody can tap.
    ScheduleView.MONTH -> {
        val first = anchor.withDayOfMonth(1)
        val last = anchor.with(TemporalAdjusters.lastDayOfMonth())
        first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to
            last.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    }

    ScheduleView.DAY -> anchor to anchor
}

/** Every date from [start] to [end] inclusive. */
private fun ScheduleView.datesOf(start: LocalDate, end: LocalDate): List<LocalDate> {
    val span = (end.toEpochDay() - start.toEpochDay()).toInt()
    return (0..span).map { offset -> start.plusDays(offset.toLong()) }
}
