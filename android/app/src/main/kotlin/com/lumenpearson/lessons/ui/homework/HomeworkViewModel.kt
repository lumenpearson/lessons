package com.lumenpearson.lessons.ui.homework

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.ui.common.SyncMessage
import com.lumenpearson.lessons.ui.common.toMessageOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Homework that shares a due date.
 *
 * Grouping is by date rather than by subject because homework is done the
 * evening before it is due — "what is due Thursday" is the only question this
 * screen has to answer well.
 */
data class HomeworkGroup(
    val date: LocalDate,
    val items: List<HomeworkItem>,
)

/**
 * @property onlyUpcoming when true, days that have already passed are hidden.
 *   Defaults to true: the common case is planning tonight, not auditing history.
 * @property hiddenCount how many items the filter is holding back, so the "все"
 *   chip can say what it would reveal.
 */
data class HomeworkUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val onlyUpcoming: Boolean = true,
    /**
     * The school's today, which is what «Сегодня» and «Завтра» mean on this
     * list and what the «только будущие» filter cuts at. Published rather than
     * read again on the screen so that the two cannot disagree - a header
     * reading «Сегодня» over a group the filter had already decided was in the
     * past is the exact failure a second `LocalDate.now()` produces.
     */
    // device clock: the placeholder for the frame before the first emission,
    // while isLoading is still true. Replaced below from the timetable.
    val today: LocalDate = LocalDate.now(),
    val groups: List<HomeworkGroup> = emptyList(),
    val hiddenCount: Int = 0,
    val message: SyncMessage? = null,
)

/** Homework tab state holder: flatten the timetable, group it, filter it. */
class HomeworkViewModel(
    private val timetableRepository: TimetableRepository,
) : ViewModel() {

    private val onlyUpcoming = MutableStateFlow(true)
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<SyncMessage?>(null)

    /**
     * Date-granularity clock so the filter's cut-off follows midnight.
     *
     * Emits an [Instant] and not a date, because the midnight that matters is
     * the school's: homework is due on a date the school wrote down, and
     * «только будущие» has to cut the list where the school's day turns over.
     * `LocalDate.now()` cut it where the *device's* did, which for a phone left
     * on another zone hid tonight's homework a few hours early - or kept
     * yesterday's on the list for a few hours after it stopped mattering.
     */
    private val ticker: Flow<Instant> = flow {
        while (true) {
            // Truncated so `distinctUntilChanged` still has something to
            // compare: a raw instant differs on every tick and would rebuild
            // this list once a minute for a cut-off that moves once a day.
            emit(Instant.now().truncatedTo(ChronoUnit.MINUTES))
            delay(DATE_TICK_MILLIS)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<HomeworkUiState> = combine(
        timetableRepository.timetable,
        ticker,
        onlyUpcoming,
        refreshing,
        message,
    ) { timetable, instant, onlyUpcoming, isRefreshing, message ->
        // No timetable means no homework to filter either, so the device's zone
        // decides nothing here beyond the date of an empty list.
        val today = timetable?.atSchool(instant)?.toLocalDate()
            // device clock: see the line above — no timetable, no school zone.
            ?: LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate()
        val all = groupsOf(timetable)
        val visible = if (onlyUpcoming) all.filter { it.date >= today } else all
        HomeworkUiState(
            isLoading = false,
            isRefreshing = isRefreshing,
            onlyUpcoming = onlyUpcoming,
            today = today,
            groups = visible,
            hiddenCount = all.sumOf { it.items.size } - visible.sumOf { it.items.size },
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = HomeworkUiState(),
    )

    /** Flips the "только будущие" / "все" filter. */
    fun setOnlyUpcoming(value: Boolean) {
        onlyUpcoming.value = value
    }

    /** Pull-to-refresh; shares the repository's single sync path. */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val result = timetableRepository.refresh()
            refreshing.value = false
            message.value = result.toMessageOrNull()
        }
    }

    /** Clears a shown snackbar so it is not repeated on the next state change. */
    fun consumeMessage() {
        message.value = null
    }

    private fun groupsOf(timetable: Timetable?): List<HomeworkGroup> =
        timetable?.days
            ?.filter { it.homework.isNotEmpty() }
            ?.sortedBy { it.date }
            ?.map { day -> HomeworkGroup(date = day.date, items = day.homework) }
            .orEmpty()

    companion object {
        private const val DATE_TICK_MILLIS = 60_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeworkViewModel(timetableRepository = Graph.container.timetableRepository)
            }
        }
    }
}
