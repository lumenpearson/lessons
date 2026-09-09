package com.lumenpearson.lessons.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.ui.common.DefaultAppSettings
import com.lumenpearson.lessons.ui.common.SyncMessage
import com.lumenpearson.lessons.ui.common.toMessageOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * @property session `null` right after signing out, for the frame before the app
 *   shell navigates away.
 * @property isRefreshing an "обновить сейчас" run is in flight.
 */
data class SettingsUiState(
    val settings: AppSettings = DefaultAppSettings,
    val session: Session? = null,
    val isRefreshing: Boolean = false,
    val message: SyncMessage? = null,
)

/**
 * Settings state holder.
 *
 * Every setter is a one-line delegation to `SettingsRepository.update`, and that
 * is the point: preferences have exactly one owner, so a toggle here reaches the
 * widget and the sync worker without this class knowing either exists. Changing
 * the sync interval likewise only writes the number —
 * [com.lumenpearson.lessons.LessonsApplication] observes it and reschedules the
 * work, which keeps WorkManager out of the UI layer entirely.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val sessionRepository: SessionRepository,
    private val timetableRepository: TimetableRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<SyncMessage?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        sessionRepository.session,
        refreshing,
        message,
    ) { settings, session, isRefreshing, message ->
        SettingsUiState(
            settings = settings,
            session = session,
            isRefreshing = isRefreshing,
            message = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
    )

    /** Material You colours from the wallpaper (Android 12+ only). */
    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    /** True black in dark mode; saves power on OLED and looks better at night. */
    fun setPitchBlack(enabled: Boolean) = update { it.copy(pitchBlack = enabled) }

    /** Whether lesson rows show the teacher's name. */
    fun setShowTeacher(enabled: Boolean) = update { it.copy(showTeacher = enabled) }

    /** Whether the widget draws the lesson progress bar. */
    fun setWidgetShowProgress(enabled: Boolean) = update { it.copy(widgetShowProgress = enabled) }

    /** Background sync cadence, in minutes. */
    fun setSyncInterval(minutes: Int) = update { it.copy(syncIntervalMinutes = minutes) }

    /** Points the app at a different server; takes effect on the next sync. */
    fun setBaseUrl(url: String) = update { it.copy(baseUrl = url) }

    /** Immediate sync, for when a user has been told "я обновил расписание". */
    fun refreshNow() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            val result = timetableRepository.refresh()
            refreshing.value = false
            message.value = result.toMessageOrNull()
        }
    }

    /**
     * Leaves the class. Navigation is not triggered from here: the session flow
     * emits `null`, and the app shell takes the user back to the join screen.
     */
    fun signOut() {
        viewModelScope.launch { sessionRepository.signOut() }
    }

    /** Clears a shown snackbar. */
    fun consumeMessage() {
        message.value = null
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = Graph.container.settingsRepository,
                    sessionRepository = Graph.container.sessionRepository,
                    timetableRepository = Graph.container.timetableRepository,
                )
            }
        }
    }
}
