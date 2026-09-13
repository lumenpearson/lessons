package com.lumenpearson.lessons.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.AppSettings
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
 * @property homeworkFirst promote homework above the timeline. Under
 *   [com.lumenpearson.lessons.core.model.TodayLayout.AUTOMATIC] this mirrors
 *   the widget: after the last bell, homework *is* the screen.
 * @property showHero draw the countdown card; a user setting.
 * @property wholeDay [remainingLessons] holds the whole day rather than the
 *   rest of it, which is also what the section is titled from.
 * @property homeworkPreview how many homework rows to draw before deferring to
 *   the tab.
 * @property showEvents draw [events] at all; a user setting.
 * @property syncedAtEpochMillis drives the "обновлено в …" footer so a stale
 *   timetable is visibly stale.
 */
data class TodayUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    // device clock: the placeholder for the frame before the first emission,
    // while isLoading is still true. `buildState` reads the school's.
    val now: LocalDateTime = LocalDateTime.now(),
    val className: String? = null,
    val state: DayState? = null,
    val today: SchoolDay? = null,
    val remainingLessons: List<Lesson> = emptyList(),
    val events: List<SchoolEvent> = emptyList(),
    val homeworkDay: SchoolDay? = null,
    val homeworkFirst: Boolean = false,
    val showTeacher: Boolean = true,
    val showHero: Boolean = true,
    val wholeDay: Boolean = false,
    val homeworkPreview: Int = AppSettings.DEFAULT_HOMEWORK_PREVIEW,
    val showEvents: Boolean = true,
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

    /**
     * The clock, re-emitted every [TICK_MILLIS]; the only source of "now".
     *
     * An [Instant] rather than a [LocalDateTime], because the wall time this
     * screen works in belongs to the school and not to the device: the schedule
     * is stored in the class's zone, and a parent in Moscow following a school
     * in Vladivostok was shown their own clock against its bells — seven hours
     * of a headline and a countdown that were simply about a different moment.
     * The instant is the one form of "now" that survives the trip from here to
     * [buildState], where the timetable that knows the zone is in hand.
     */
    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now())
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
    ) { timetable, instant, settings, isRefreshing, message ->
        buildState(
            timetable = timetable,
            instant = instant,
            settings = settings,
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
        instant: Instant,
        settings: AppSettings,
        isRefreshing: Boolean,
        message: SyncMessage?,
    ): TodayUiState {
        if (timetable == null) {
            // No timetable, so no school zone to be in: the device's own is the
            // only answer there is, and all it decides here is which date the
            // "no data" card names.
            // device clock: deliberate, and the comment above says why.
            val deviceNow = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            return TodayUiState(
                isLoading = false,
                isRefreshing = isRefreshing,
                now = deviceNow,
                state = DayState.NoData(deviceNow.toLocalDate()),
                showTeacher = settings.showTeacher,
                showHero = settings.todayShowHero,
                wholeDay = settings.todayWholeDay,
                homeworkPreview = settings.todayHomeworkPreview,
                showEvents = settings.todayShowEvents,
                message = message,
            )
        }

        val now = timetable.atSchool(instant)
        val today = timetable.day(now.toLocalDate())
        val dayState = ScheduleEngine.stateAt(timetable, now)
        // homeworkFocus is non-null exactly for the two "school is over" states,
        // which is also exactly when homework should lead the screen — unless
        // the user has taken the decision away from the clock.
        val focus = dayState.homeworkFocus

        return TodayUiState(
            isLoading = false,
            isRefreshing = isRefreshing,
            now = now,
            className = timetable.schoolClass.name,
            state = dayState,
            today = today,
            remainingLessons = when {
                today == null -> emptyList()
                settings.todayWholeDay -> today.activeLessons
                else -> ScheduleEngine.remainingLessons(today, now.toLocalTime())
            },
            events = today?.events?.sortedBy { it.startsAt }.orEmpty(),
            homeworkDay = focus ?: today?.takeIf { it.homework.isNotEmpty() },
            homeworkFirst = settings.todayLayout.homeworkLeads(schoolIsOver = focus != null),
            showTeacher = settings.showTeacher,
            showHero = settings.todayShowHero,
            wholeDay = settings.todayWholeDay,
            homeworkPreview = settings.todayHomeworkPreview,
            showEvents = settings.todayShowEvents,
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
