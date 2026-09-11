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

    /** Date-granularity clock so the filter's cut-off follows midnight. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(DATE_TICK_MILLIS)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<HomeworkUiState> = combine(
        timetableRepository.timetable,
        today,
        onlyUpcoming,
        refreshing,
        message,
    ) { timetable, today, onlyUpcoming, isRefreshing, message ->
        val all = groupsOf(timetable)
        val visible = if (onlyUpcoming) all.filter { it.date >= today } else all
        HomeworkUiState(
            isLoading = false,
            isRefreshing = isRefreshing,
            onlyUpcoming = onlyUpcoming,
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
