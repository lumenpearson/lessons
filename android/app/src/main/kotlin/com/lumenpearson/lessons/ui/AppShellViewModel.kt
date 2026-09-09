package com.lumenpearson.lessons.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.ui.common.DefaultAppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The two facts the app shell needs before it can draw anything:
 *
 * @property settings theme preferences, applied by `LessonsTheme`.
 * @property signedIn `null` while the session is still being read from disk,
 *   which is the signal to show the splash instead of guessing a start
 *   destination and then yanking the user somewhere else a frame later.
 */
data class AppShellUiState(
    val settings: AppSettings = DefaultAppSettings,
    val signedIn: Boolean? = null,
)

/**
 * Owns the state that outlives any single screen: theme preferences and whether
 * a class session exists.
 *
 * Keeping it here rather than in the settings screen means signing out anywhere
 * in the app is enough to bounce the user back to the join screen — navigation
 * reacts to the session, no callback plumbing required.
 */
class AppShellViewModel(
    private val sessionRepository: SessionRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * Tri-state sign-in flag. Seeded from [SessionRepository.current] so the very
     * first value is authoritative, then kept live by the session flow.
     */
    private val signedIn = MutableStateFlow<Boolean?>(null)

    val uiState: StateFlow<AppShellUiState> =
        combine(settingsRepository.settings, signedIn) { settings, session ->
            AppShellUiState(settings = settings, signedIn = session)
        }.stateIn(
            scope = viewModelScope,
            // Eagerly: the theme must be ready before the first frame, and this
            // view model lives for the whole process anyway.
            started = SharingStarted.Eagerly,
            initialValue = AppShellUiState(),
        )

    init {
        viewModelScope.launch {
            signedIn.value = sessionRepository.current() != null
            sessionRepository.session.collect { session -> signedIn.value = session != null }
        }
    }

    companion object {
        /**
         * No Hilt in this project, so every view model reaches the graph the same
         * way the widget and the sync worker do: through the process-wide
         * [Graph]. The factory keeps that lookup out of the constructor, which
         * keeps the class unit-testable with fakes.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AppShellViewModel(
                    sessionRepository = Graph.container.sessionRepository,
                    settingsRepository = Graph.container.settingsRepository,
                )
            }
        }
    }
}
