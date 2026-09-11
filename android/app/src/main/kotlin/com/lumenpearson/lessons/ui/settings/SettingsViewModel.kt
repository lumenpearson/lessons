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
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.ThemeMode
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

    /** Light, dark, or whatever the system is doing. */
    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    /** Material You colours from the wallpaper (Android 12+ only). */
    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }

    /** True black in dark mode; saves power on OLED and looks better at night. */
    fun setPitchBlack(enabled: Boolean) = update { it.copy(pitchBlack = enabled) }

    /** Master switch for every haptic in the app. */
    fun setHapticsEnabled(enabled: Boolean) = update { it.copy(hapticsEnabled = enabled) }

    /** How hard the app taps back. */
    fun setHapticStrength(strength: HapticStrength) = update { it.copy(hapticStrength = strength) }

    /** Whether the four tabs can be swiped between, or only tapped. */
    fun setSwipeTabs(enabled: Boolean) = update { it.copy(swipeTabs = enabled) }

    /** Which tab the app opens on, and which one Back returns to. */
    fun setDefaultTab(tab: HomeTab) = update { it.copy(defaultTab = tab) }

    /** Blur lists along their scroll axis while they are moving. */
    fun setMotionBlur(enabled: Boolean) = update { it.copy(motionBlur = enabled) }

    /** How strong that blur is; see `AppSettings.MOTION_BLUR_SCALE_RANGE`. */
    fun setMotionBlurScale(scale: Float) = update { it.copy(motionBlurScale = scale) }

    /** Fade content out under the status bar. */
    fun setEdgeBlur(enabled: Boolean) = update { it.copy(edgeBlur = enabled) }

    /** Whether lesson rows show the teacher's name. */
    fun setShowTeacher(enabled: Boolean) = update { it.copy(showTeacher = enabled) }

    /** Whether the widget draws the lesson progress bar. */
    fun setWidgetShowProgress(enabled: Boolean) = update { it.copy(widgetShowProgress = enabled) }

    /**
     * Whether a crash leaves a report behind.
     *
     * Off by default and never turned on by the app itself: a report carries the
     * device model and the app's own recent activity, and that is the user's to
     * hand over, not ours to collect.
     */
    fun setDebugMode(enabled: Boolean) = update { it.copy(debugMode = enabled) }

    /**
     * Records that the first-run introduction has been seen.
     *
     * Written when the introduction reaches the class-code step rather than when
     * a class is actually joined: a pupil who backs out at the code field has
     * still read the four screens, and making them read them again is a
     * punishment for hesitating.
     */
    fun setOnboardingDone() = update { it.copy(onboardingDone = true) }

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
