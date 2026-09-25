package com.lumenpearson.lessons.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.ui.common.DefaultAppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The two facts the app shell needs before it can draw anything:
 *
 * @property settings theme preferences, applied by `LessonsTheme`.
 * @property shell which home the phone has and whether onboarding holds the
 *   screen — `null` while it is still being read from disk, which is the signal
 *   to show the splash instead of guessing a start destination and then
 *   yanking the user somewhere else a frame later. `RootGate.rootScreen` and
 *   `ShellHome.shellHome` turn it into a screen.
 * @property settingsLoaded whether [settings] is what is stored or the
 *   placeholder this state is constructed with. False for exactly one value —
 *   the `initialValue` below — because the combine that builds every other one
 *   cannot run until the settings flow has emitted. Most of the app may read
 *   the placeholder happily: a frame of the default theme before the stored one
 *   arrives is a frame, and the next one corrects it. It is here for the reader
 *   that cannot be corrected afterwards; see [languageToApply].
 */
data class AppShellUiState(
    val settings: AppSettings = DefaultAppSettings,
    val shell: ShellState? = null,
    val settingsLoaded: Boolean = false,
) {

    /**
     * The language to put the running activity in, or `null` for "do not act".
     *
     * A separate question from [AppSettings.language], and the difference is the
     * whole point. [DefaultAppSettings] says [AppLanguage.SYSTEM] — not because
     * anybody chose it, but because that is what a `data class` default is — so
     * on the first frame the placeholder is indistinguishable from a reader who
     * has genuinely asked for the phone's language. `MainActivity` compares that
     * value against the one it really was attached in, and below API 33 answers
     * a difference with `recreate()`: every launch by somebody who had chosen
     * «Русский» or «English» looked like a language change and threw the
     * activity away.
     *
     * Stated here, beside the placeholder, rather than as a condition in the
     * effect that consumes it, so that it can be asked on the JVM.
     */
    val languageToApply: AppLanguage? get() = settings.language.takeIf { settingsLoaded }
}

/**
 * Owns the state that outlives any single screen: theme preferences and which
 * home the phone has.
 *
 * Keeping it here rather than in the settings screen means signing out anywhere
 * in the app is enough to move the user on — to the way in, or from the class
 * to the diary home when a diary is still signed in — with no callback
 * plumbing: navigation reacts to the stored mode.
 *
 * Before the first read, the hold a previous process may have left behind is
 * settled ([ShellModeSource.settleColdStart]) — once per process, through
 * [coldStart], because this view model is rebuilt with every activity while the
 * hold a *running* onboarding just set must not be dropped by the rebuild.
 */
class AppShellViewModel(
    private val shellMode: ShellModeSource,
    settingsRepository: SettingsRepository,
    private val coldStart: ColdStart = ColdStart.Process,
) : ViewModel() {

    /**
     * The shell's state. Seeded from the settled cold start so the very first
     * value is authoritative, then kept live by the mode's flow.
     */
    private val shell = MutableStateFlow<ShellState?>(null)

    val uiState: StateFlow<AppShellUiState> =
        combine(settingsRepository.settings, shell) { settings, shell ->
            // `settingsLoaded = true` unconditionally, and that is sound rather
            // than optimistic: combine emits nothing until every source has, so
            // reaching this line is itself the proof that the settings flow has
            // answered. The one state that never comes through here is the
            // initialValue below.
            AppShellUiState(settings = settings, shell = shell, settingsLoaded = true)
        }.stateIn(
            scope = viewModelScope,
            // Eagerly: the theme must be ready before the first frame, and this
            // view model lives for the whole process anyway.
            started = SharingStarted.Eagerly,
            initialValue = AppShellUiState(),
        )

    init {
        viewModelScope.launch {
            shell.value = coldStart.settle(shellMode)
            shellMode.state.collect { shell.value = it }
        }
    }

    /**
     * «Once per process», as a thing that can be handed to the constructor: the
     * app passes [Process], which is shared by every view model the process
     * builds, and a test passes a fresh one so its cases do not share a process.
     */
    class ColdStart {

        private val lock = Mutex()
        private var settled = false

        /**
         * The first call settles the stored hold and answers with the state
         * after it; every later call only reads.
         */
        suspend fun settle(source: ShellModeSource): ShellState = lock.withLock {
            if (settled) {
                source.current()
            } else {
                source.settleColdStart().also { settled = true }
            }
        }

        companion object {
            /** The one this process uses. */
            val Process = ColdStart()
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
                    shellMode = Graph.container.shellMode,
                    settingsRepository = Graph.container.settingsRepository,
                )
            }
        }
    }
}
