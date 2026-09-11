package com.lumenpearson.lessons.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.ScheduleEngine
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.core.model.homeworkFocus
import com.lumenpearson.lessons.ui.common.SyncMessage
import com.lumenpearson.lessons.ui.common.toMessageOrNull
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the home screen paints, derived once per tick.
 *
 * @property state what is happening now; `null` only before the first timetable
 *   emission, which is the loading case.
 * @property remainingLessons today's lessons that have not finished yet — the
 *   home screen deliberately does not re-list the morning at 15:00.
 * @property homeworkDay the day whose homework is worth reading right now: the
 *   next school day once lessons are over, otherwise today.
 * @property homeworkFirst promote homework above the timeline. Mirrors the
 *   widget: after the last bell, homework *is* the screen.
 * @property syncedAtEpochMillis drives the "обновлено в …" footer so a stale
 *   timetable is visibly stale.
 */
data class TodayUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val now: LocalDateTime = LocalDateTime.now(),
    val className: String? = null,
    val state: DayState? = null,
    val today: SchoolDay? = null,
    val remainingLessons: List<Lesson> = emptyList(),
    val events: List<SchoolEvent> = emptyList(),
    val homeworkDay: SchoolDay? = null,
    val homeworkFirst: Boolean = false,
    val showTeacher: Boolean = true,
    val syncedAtEpochMillis: Long = 0L,
    val message: SyncMessage? = null,
)

/**
 * Home screen state holder.
 *
 * The interesting part is the ticker: [DayState] is a function of the wall
 * clock, so the screen has to be recomputed on a schedule rather than only when
 * data changes. 30 seconds is the coarsest interval at which a countdown to the
 * bell still looks alive, and it costs one recomposition of a cheap tree.
 */
class TodayViewModel(
    private val timetableRepository: TimetableRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Wall clock, re-emitted every [TICK_MILLIS]; the only source of "now". */
    private val ticker: Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(TICK_MILLIS)
        }
    }

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<SyncMessage?>(null)

    val uiState: StateFlow<TodayUiState> = combine(
        timetableRepository.timetable,
        ticker,
        settingsRepository.settings,
        refreshing,
        message,
    ) { timetable, now, settings, isRefreshing, message ->
        buildState(
            timetable = timetable,
            now = now,
            showTeacher = settings.showTeacher,
            isRefreshing = isRefreshing,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = TodayUiState(),
    )

    /** Pull-to-refresh and the widget's "обновить" both land here. */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val result = timetableRepository.refresh()
            refreshing.value = false
            message.value = result.toMessageOrNull()
        }
    }

    /** Called once the snackbar has been shown, so it is not shown again. */
    fun consumeMessage() {
        message.value = null
    }

    private fun buildState(
        timetable: Timetable?,
        now: LocalDateTime,
        showTeacher: Boolean,
        isRefreshing: Boolean,
        message: SyncMessage?,
    ): TodayUiState {
        if (timetable == null) {
            return TodayUiState(
                isLoading = false,
                isRefreshing = isRefreshing,
                now = now,
                state = DayState.NoData(now.toLocalDate()),
                showTeacher = showTeacher,
                message = message,
            )
        }

        val today = timetable.day(now.toLocalDate())
        val dayState = ScheduleEngine.stateAt(timetable, now)
        // homeworkFocus is non-null exactly for the two "school is over" states,
        // which is also exactly when homework should lead the screen.
        val focus = dayState.homeworkFocus

        return TodayUiState(
            isLoading = false,
            isRefreshing = isRefreshing,
            now = now,
            className = timetable.schoolClass.name,
            state = dayState,
            today = today,
            remainingLessons = today
                ?.let { ScheduleEngine.remainingLessons(it, now.toLocalTime()) }
                .orEmpty(),
            events = today?.events?.sortedBy { it.startsAt }.orEmpty(),
            homeworkDay = focus ?: today?.takeIf { it.homework.isNotEmpty() },
            homeworkFirst = focus != null,
            showTeacher = showTeacher,
            syncedAtEpochMillis = timetable.syncedAtEpochMillis,
            message = message,
        )
    }

    companion object {
        private const val TICK_MILLIS = 30_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TodayViewModel(
                    timetableRepository = Graph.container.timetableRepository,
                    settingsRepository = Graph.container.settingsRepository,
                )
            }
        }
    }
}
