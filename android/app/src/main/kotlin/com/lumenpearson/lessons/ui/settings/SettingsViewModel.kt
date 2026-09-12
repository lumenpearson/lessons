package com.lumenpearson.lessons.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.IssueDraft
import com.lumenpearson.lessons.core.data.repository.IssueResult
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.data.repository.UpdateCheck
import com.lumenpearson.lessons.core.data.repository.UpdateRepository
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.AppFont
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.core.model.TodayLayout
import com.lumenpearson.lessons.core.model.WeekStart
import com.lumenpearson.lessons.ui.common.DefaultAppSettings
import com.lumenpearson.lessons.ui.common.SyncMessage
import com.lumenpearson.lessons.ui.common.toMessageOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    /** Where the last update check stands; see [UpdateCheck]. */
    val update: UpdateCheck = UpdateCheck.Idle,
    /** The signed-in GitHub account, or `null`. */
    val github: GithubAccount? = null,
    /** Where the Telegram link of this phone stands; see [DeviceLinkState]. */
    val deviceLink: DeviceLinkState = DeviceLinkState.Idle,
    /** Whether this build can sign in at all; the row hides otherwise. */
    val githubConfigured: Boolean = false,
    /** The device flow, for the sign-in sheet. */
    val signIn: DeviceFlow = DeviceFlow.Idle,
    /** An issue is on its way to GitHub. */
    val isFilingIssue: Boolean = false,
    /**
     * The release sheet is up. Held here rather than in a screen because two
     * places raise it — the updates page on a tap, the shell when the check at
     * launch finds something — and one host in the shell shows it for both.
     */
    val showReleaseSheet: Boolean = false,
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
    private val updateRepository: UpdateRepository,
    private val githubRepository: GithubRepository,
    private val deviceLinkRepository: DeviceLinkRepository,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<SyncMessage?>(null)
    private val filingIssue = MutableStateFlow(false)
    private val releaseSheet = MutableStateFlow(false)

    /** The installed version, with build suffixes stripped. */
    val installedVersion: String get() = updateRepository.installedVersion

    // Two combines rather than one: `combine` stops at five flows before it
    // wants an array, and the two halves change for unrelated reasons.
    private val local = combine(
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
    }

    private val remote = combine(
        updateRepository.state,
        githubRepository.account,
        githubRepository.flow,
        filingIssue,
        releaseSheet,
    ) { update, account, signIn, filing, sheet ->
        Remote(update, account, signIn, filing, sheet)
    }

    private val deviceLink = MutableStateFlow<DeviceLinkState>(DeviceLinkState.Idle)

    val uiState: StateFlow<SettingsUiState> = combine(local, remote, deviceLink) { state, remote, link ->
        state.copy(
            update = remote.update,
            github = remote.account,
            githubConfigured = githubRepository.isConfigured,
            signIn = remote.signIn,
            isFilingIssue = remote.filing,
            showReleaseSheet = remote.sheet,
            deviceLink = link,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = SettingsUiState(),
    )

    /**
     * Asks the server whether this phone is tied to a Telegram account.
     *
     * Called when the class page opens. Cheap to repeat: the server hands out
     * the same link code until it is used, so opening the page twice does not
     * invalidate the code the user is halfway through typing into the bot.
     */
    fun refreshDeviceLink() {
        if (deviceLink.value is DeviceLinkState.Loading) return
        val known = (deviceLink.value as? DeviceLinkState.Ready)?.link
        deviceLink.value = DeviceLinkState.Loading(known)
        viewModelScope.launch {
            deviceLinkRepository.refresh()
                .onSuccess { deviceLink.value = DeviceLinkState.Ready(it) }
                .onFailure { deviceLink.value = DeviceLinkState.Failed(it, known) }
        }
    }

    /** Unties the phone from its Telegram account; read-only afterwards. */
    fun unlinkDevice() {
        val known = (deviceLink.value as? DeviceLinkState.Ready)?.link
        deviceLink.value = DeviceLinkState.Loading(known)
        viewModelScope.launch {
            deviceLinkRepository.unlink()
                .onSuccess { deviceLink.value = DeviceLinkState.Ready(it) }
                .onFailure { deviceLink.value = DeviceLinkState.Failed(it, known) }
        }
    }

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

    /** The bundled face or the phone's own; see `AppSettings.appFont`. */
    fun setAppFont(font: AppFont) = update { it.copy(appFont = font) }

    /**
     * Writes the choice and nothing else. Putting the locale on is the
     * activity's job — see `AppLocales` — because it is the activity's context
     * that carries a language, and because on API 33+ the platform, not this
     * class, decides when the screen restarts.
     */
    fun setLanguage(language: AppLanguage) = update { it.copy(language = language) }

    /** One of `AppSettings.TEXT_SCALE_OPTIONS`; anything else is clamped on read. */
    fun setTextScale(scale: Float) = update { it.copy(textScale = scale) }

    /** Master switch for the app's own animations; off makes them instant. */
    fun setAnimations(enabled: Boolean) = update { it.copy(animations = enabled) }

    /** How fast it moves; see `AppSettings.MOTION_SPEED_RANGE`. */
    fun setMotionSpeed(speed: Float) = update { it.copy(motionSpeed = speed) }

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

    /** The countdown card at the top of the home screen. */
    fun setTodayShowHero(enabled: Boolean) = update { it.copy(todayShowHero = enabled) }

    /** Which of the home screen's two big blocks leads; see [TodayLayout]. */
    fun setTodayLayout(layout: TodayLayout) = update { it.copy(todayLayout = layout) }

    /** Whether the home screen lists the whole day or only what is left of it. */
    fun setTodayWholeDay(enabled: Boolean) = update { it.copy(todayWholeDay = enabled) }

    /** One of `AppSettings.HOMEWORK_PREVIEW_OPTIONS`; anything else is clamped on read. */
    fun setTodayHomeworkPreview(count: Int) = update { it.copy(todayHomeworkPreview = count) }

    /** Whether the home screen lists today's events. */
    fun setTodayShowEvents(enabled: Boolean) = update { it.copy(todayShowEvents = enabled) }

    /** Which date the calendar's week strip begins on; see [WeekStart]. */
    fun setWeekStart(start: WeekStart) = update { it.copy(weekStart = start) }

    /** Whether Saturday and Sunday appear in the week strip. */
    fun setWeekShowWeekends(enabled: Boolean) = update { it.copy(weekShowWeekends = enabled) }

    /** The lesson-count dots under each date in the calendar. */
    fun setWeekShowLoad(enabled: Boolean) = update { it.copy(weekShowLoad = enabled) }

    /** Whether the calendar shows a day's events under its lessons. */
    fun setWeekShowEvents(enabled: Boolean) = update { it.copy(weekShowEvents = enabled) }

    /** Whether the calendar shows a day's homework under its lessons. */
    fun setWeekShowHomework(enabled: Boolean) = update { it.copy(weekShowHomework = enabled) }

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

    /**
     * Everything the app is allowed to interrupt the user about.
     *
     * One setter over the whole block rather than seven: the repository re-arms
     * the alarm chain when this value changes, and it can only tell that it
     * changed if the change arrives as one write.
     */
    fun setAlerts(transform: (AlertPreferences) -> AlertPreferences) =
        update { it.copy(alerts = transform(it.alerts)) }

    /** Background sync cadence, in minutes. */
    fun setSyncInterval(minutes: Int) = update { it.copy(syncIntervalMinutes = minutes) }

    /** The full-screen liquid ripple; see `AppSettings.rippleEffects`. */
    fun setRippleEffects(enabled: Boolean) = update { it.copy(rippleEffects = enabled) }

    /** The circular wipe on a theme change; see `AppSettings.themeReveal`. */
    fun setThemeReveal(enabled: Boolean) = update { it.copy(themeReveal = enabled) }

    /** Check GitHub for a newer release at launch. */
    fun setAutoCheckUpdates(enabled: Boolean) = update { it.copy(autoCheckUpdates = enabled) }

    /** Whether pre-releases count as updates. */
    fun setIncludePrerelease(enabled: Boolean) = update { it.copy(includePrerelease = enabled) }

    /** Raise the release sheet when the automatic check finds something. */
    fun setNotifyNewUpdates(enabled: Boolean) = update { it.copy(notifyNewUpdates = enabled) }

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

    /**
     * Asks GitHub whether there is something newer, and shows the answer.
     *
     * Manual: the pre-release setting is read at the moment of the tap, so
     * flipping the switch and tapping "проверить" does what it looks like it
     * does. The sheet is raised whatever the verdict — "всё актуально" with the
     * installed release's notes is an answer too, and the one Essentials gives.
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            val includePrerelease = settingsRepository.settings.first().includePrerelease
            updateRepository.check(includePrerelease)
            releaseSheet.value = true
        }
    }

    /**
     * The check the app runs on its own at launch.
     *
     * Quieter than [checkForUpdates] in every way: it only runs when the
     * setting allows, at most once a day, and it raises the sheet only for a
     * release that is new, wanted, and not one the user has already said
     * "позже" to. A failure is not shown at all — nobody asked.
     */
    fun checkForUpdatesAtLaunch() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.autoCheckUpdates) return@launch
            val last = updateRepository.lastCheckMillis() ?: 0L
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MILLIS) return@launch
            val verdict = updateRepository.check(settings.includePrerelease)
            if (verdict !is UpdateCheck.Available || !settings.notifyNewUpdates) return@launch
            if (verdict.release.tag == updateRepository.dismissedTag.value) return@launch
            releaseSheet.value = true
        }
    }

    /** Raises the release sheet over whatever the last check found. */
    fun showReleaseSheet() {
        releaseSheet.value = true
        // A sheet over "ещё не проверялось" would be a sheet about nothing.
        if (updateRepository.state.value == UpdateCheck.Idle) checkForUpdates()
    }

    fun hideReleaseSheet() {
        releaseSheet.value = false
    }

    /** "Позже": stops the automatic check from raising this tag again. */
    fun dismissUpdate(tag: String) {
        releaseSheet.value = false
        viewModelScope.launch { updateRepository.dismiss(tag) }
    }

    /**
     * Turning pre-releases on is followed by a check straight away, so the
     * switch has a visible consequence rather than waiting for the next launch.
     */
    fun enablePrereleases() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(includePrerelease = true) }
            updateRepository.check(includePrerelease = true)
            releaseSheet.value = true
        }
    }

    fun signInWithGithub() = githubRepository.signIn()

    fun cancelGithubSignIn() = githubRepository.cancelSignIn()

    fun signOutOfGithub() {
        viewModelScope.launch { githubRepository.signOut() }
    }

    /**
     * Files a bug report as the signed-in user.
     *
     * @param onFiled called with the new issue's page on success, so the sheet
     *   can offer to open it. Failure surfaces as a snackbar.
     */
    fun fileIssue(draft: IssueDraft, onFiled: (url: String) -> Unit) {
        if (filingIssue.value) return
        viewModelScope.launch {
            filingIssue.value = true
            val result = githubRepository.fileIssue(draft)
            filingIssue.value = false
            when (result) {
                is IssueResult.Filed -> onFiled(result.htmlUrl)
                is IssueResult.Failed -> message.value = SyncMessage.IssueFailed
            }
        }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** Once a day is plenty for a school app; see `UpdateRepository.lastCheckMillis`. */
        private const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = Graph.container.settingsRepository,
                    sessionRepository = Graph.container.sessionRepository,
                    timetableRepository = Graph.container.timetableRepository,
                    updateRepository = Graph.container.updateRepository,
                    githubRepository = Graph.container.githubRepository,
                    deviceLinkRepository = Graph.container.deviceLinkRepository,
                )
            }
        }
    }
}

/** The half of the state that comes from GitHub rather than from disk. */
private data class Remote(
    val update: UpdateCheck,
    val account: GithubAccount?,
    val signIn: DeviceFlow,
    val filing: Boolean,
    val sheet: Boolean,
)

/**
 * The Telegram link as the class page sees it.
 *
 * [Loading] and [Failed] carry the last good answer so a refresh that is in
 * flight, or one that failed, does not blank a card that was showing a code
 * the user may be typing into the bot right now.
 */
sealed interface DeviceLinkState {
    /** Nobody has asked yet; the page asks when it opens. */
    data object Idle : DeviceLinkState

    data class Loading(val known: DeviceLink?) : DeviceLinkState

    data class Ready(val link: DeviceLink) : DeviceLinkState

    data class Failed(val cause: Throwable, val known: DeviceLink?) : DeviceLinkState
}
