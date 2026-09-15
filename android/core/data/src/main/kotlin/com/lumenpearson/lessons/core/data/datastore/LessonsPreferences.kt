package com.lumenpearson.lessons.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySessionStore
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.AppFont
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.LessonAlertDetail
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.core.model.TodayLayout
import com.lumenpearson.lessons.core.model.WeekStart
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/**
 * One preferences file for the whole app. DataStore forbids opening the same
 * file twice in a process, so the delegate lives here, at top level, and
 * [LessonsPreferences] is the only thing that touches it.
 */
private val Context.lessonsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lessons",
    // The read side degrades a corrupt file to defaults, but every `edit` reads
    // the file first and rethrows, so without this a single truncated write —
    // the phone losing power mid-fsync — left a store that could never be
    // written to again: no sign-in, no settings, no sign-out, for the life of
    // the install. Replacing the file loses what was in it, which is what a
    // corrupt file has already done.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Typed access to the key-value store behind [Session] and [AppSettings].
 *
 * Session and settings share a file on purpose: they are written from the same
 * screens, they are both tiny, and a single file means a single fsync and a
 * single flow to observe.
 */
internal class LessonsPreferences(context: Context) : DiarySessionStore {

    private val dataStore = context.applicationContext.lessonsDataStore

    /**
     * A corrupt or unreadable file must not take the app down: it degrades to
     * "no session, default settings", which lands the user on the join screen -
     * recoverable, unlike a crash loop at startup.
     */
    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    /** The class being shown, or `null` when this device is in none. */
    val session: Flow<Session?> = preferences.map { it.activeMembership() }.distinctUntilChanged()

    /** Every class this device has joined, in the order they were joined. */
    val sessions: Flow<List<Session>> = preferences.map { it.memberships() }.distinctUntilChanged()

    val settings: Flow<AppSettings> = preferences.map { it.toSettings() }.distinctUntilChanged()

    suspend fun currentSession(): Session? = preferences.first().activeMembership()

    suspend fun currentSessions(): List<Session> = preferences.first().memberships()

    suspend fun currentSettings(): AppSettings = preferences.first().toSettings()

    /**
     * Stores a membership and makes it the one being shown.
     *
     * Re-joining a class already on this phone replaces its token **in place**
     * rather than appending: the join code is how a pupil recovers from a
     * revoked device, and doing that must not leave the same class listed
     * twice, nor move it to the bottom of a list the user has got used to.
     */
    suspend fun addSession(value: Session) {
        dataStore.edit { prefs ->
            prefs.writeMemberships(prefs.memberships().withMembership(value))
            prefs.activate(value.classId)
        }
    }

    /**
     * Shows a different class. A class this device is not in is ignored rather
     * than stored: the active id is read back by matching it against the list,
     * and one that matches nothing would land the app on the join screen with
     * memberships still on disk.
     */
    suspend fun selectSession(classId: Long) {
        dataStore.edit { prefs ->
            if (prefs.memberships().none { it.classId == classId }) return@edit
            prefs.activate(classId)
        }
    }

    /**
     * Drops one membership, keeping the rest.
     *
     * If it was the one being shown, the first of the remaining classes takes
     * over — there is no "no class selected" state to fall into while the phone
     * still belongs somewhere. Leaving the last one is a full sign-out, and
     * lands on the same keys [clearSession] would leave behind.
     */
    suspend fun removeSession(classId: Long) {
        dataStore.edit { prefs ->
            val remaining = prefs.memberships().filterNot { it.classId == classId }
            prefs.writeMemberships(remaining)
            val next = remaining.firstOrNull { it.classId == prefs[MembershipKeys.ACTIVE_CLASS_ID] }
                ?: remaining.firstOrNull()
            if (next == null) {
                prefs[KEY_ONBOARDING_DONE] = true
                prefs.remove(MembershipKeys.ACTIVE_CLASS_ID)
                prefs.remove(KEY_SCHEDULE_FINGERPRINT)
            } else {
                prefs.activate(next.classId)
            }
        }
    }

    /**
     * Clears identity only; the server address stays so re-joining is one field.
     *
     * The diary keys are not in here on purpose. Leaving a class is not leaving
     * the diary: they are two accounts, and the one being signed out of is the
     * one the user pressed a button about. [clearDiarySession] is the other
     * half, and it is just as narrow.
     */
    suspend fun clearSession() {
        dataStore.edit { prefs ->
            // Written on the way out, because the token this is about to remove
            // is what the read side uses to recognise somebody who predates the
            // introduction flag. To be here at all you were in a class, and to
            // have been in a class you got past the join screen.
            prefs[KEY_ONBOARDING_DONE] = true
            // Both the list and the four keys it replaced: the old ones are
            // what an install from before this version is read from, and
            // leaving them would sign the user back in to that one class on the
            // next launch.
            prefs.clearMemberships()
            // The fingerprint describes the shape of *that* class's schedule, so
            // keeping it means the first sync after joining a different one
            // compares two unrelated timetables, finds them different, and
            // announces that the schedule changed seconds after joining —
            // exactly the noise the baseline rule exists to prevent.
            prefs.remove(KEY_SCHEDULE_FINGERPRINT)
        }
    }

    // ---------------------------------------------------------------------
    // The diary's own session
    // ---------------------------------------------------------------------
    //
    // Separate keys, separate reads, separate writes, and nothing below touches
    // the pair above. The two accounts are independent on the server — see
    // `current_diary` in `server/app/api/diary.py` — so leaving a class must
    // not sign a parent out of the diary, and signing out of the diary must not
    // unjoin the class. Writing them into one [Session] would have made that a
    // matter of remembering; two sets of keys makes it a matter of which method
    // is called.

    override val diarySession: Flow<DiarySession?> =
        preferences.map { it.toDiarySession() }.distinctUntilChanged()

    override suspend fun currentDiarySession(): DiarySession? =
        preferences.first().toDiarySession()

    override suspend fun writeDiarySession(value: DiarySession) {
        dataStore.edit { prefs ->
            prefs[KEY_DIARY_TOKEN] = value.token
            prefs[KEY_DIARY_LOGIN] = value.login
        }
    }

    /** Clears the diary and only the diary; [clearSession] is its counterpart. */
    override suspend fun clearDiarySession() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_DIARY_TOKEN)
            prefs.remove(KEY_DIARY_LOGIN)
        }
    }

    /** Read-modify-write inside DataStore's transaction, so concurrent edits merge. */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[KEY_BASE_URL] = updated.baseUrl.trim()
            prefs[KEY_THEME_MODE] = updated.themeMode.name
            prefs[KEY_DYNAMIC_COLOR] = updated.dynamicColor
            prefs[KEY_PITCH_BLACK] = updated.pitchBlack
            prefs[KEY_HAPTICS] = updated.hapticsEnabled
            prefs[KEY_HAPTIC_STRENGTH] = updated.hapticStrength.name
            prefs[KEY_APP_FONT] = updated.appFont.name
            prefs[KEY_LANGUAGE] = updated.language.name
            prefs[KEY_TEXT_SCALE] = updated.textScale.coerceIn(AppSettings.TEXT_SCALE_RANGE)
            prefs[KEY_ANIMATIONS] = updated.animations
            prefs[KEY_MOTION_SPEED] = updated.motionSpeed.coerceIn(AppSettings.MOTION_SPEED_RANGE)
            prefs[KEY_SWIPE_TABS] = updated.swipeTabs
            prefs[KEY_DEFAULT_TAB] = updated.defaultTab.name
            prefs[KEY_MOTION_BLUR] = updated.motionBlur
            prefs[KEY_MOTION_BLUR_SCALE] = updated.motionBlurScale
                .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE)
            prefs[KEY_EDGE_BLUR] = updated.edgeBlur
            prefs[KEY_SHOW_TEACHER] = updated.showTeacher
            prefs[KEY_WIDGET_SHOW_PROGRESS] = updated.widgetShowProgress
            prefs[KEY_TODAY_SHOW_HERO] = updated.todayShowHero
            prefs[KEY_TODAY_LAYOUT] = updated.todayLayout.name
            prefs[KEY_TODAY_WHOLE_DAY] = updated.todayWholeDay
            prefs[KEY_TODAY_HOMEWORK_PREVIEW] =
                AppSettings.nearestHomeworkPreview(updated.todayHomeworkPreview)
            prefs[KEY_TODAY_SHOW_EVENTS] = updated.todayShowEvents
            prefs[KEY_WEEK_START] = updated.weekStart.name
            prefs[KEY_WEEK_WEEKENDS] = updated.weekShowWeekends
            prefs[KEY_WEEK_LOAD] = updated.weekShowLoad
            prefs[KEY_WEEK_EVENTS] = updated.weekShowEvents
            prefs[KEY_WEEK_HOMEWORK] = updated.weekShowHomework
            prefs[KEY_DEBUG_MODE] = updated.debugMode
            prefs[KEY_ONBOARDING_DONE] = updated.onboardingDone
            prefs[KEY_ALERT_LESSON] = updated.alerts.lessonSoon
            prefs[KEY_ALERT_LEAD] = updated.alerts.lessonLeadMinutes
            prefs[KEY_ALERT_MORNING] = updated.alerts.morningSummary
            prefs[KEY_ALERT_MORNING_AT] = updated.alerts.morningAtMinutes
            prefs[KEY_ALERT_HOMEWORK] = updated.alerts.homeworkReminder
            prefs[KEY_ALERT_HOMEWORK_AT] = updated.alerts.homeworkAtMinutes
            prefs[KEY_ALERT_CHANGES] = updated.alerts.scheduleChanges
            prefs[KEY_ALERT_LESSON_DETAIL] = updated.alerts.lessonDetail.name
            // Stored as a set of decimal weekday numbers. An empty set is a
            // real answer — "не показывать ни в один день" — and DataStore
            // keeps an empty set as a present key, so it survives the read
            // below rather than falling back to all seven.
            prefs[KEY_ALERT_MORNING_DAYS] = updated.alerts.morningWeekdays.map(Int::toString).toSet()
            prefs[KEY_ALERT_QUIET] = updated.alerts.quietHours
            prefs[KEY_ALERT_QUIET_FROM] = updated.alerts.quietFromMinutes
            prefs[KEY_ALERT_QUIET_TO] = updated.alerts.quietToMinutes
            prefs[KEY_ALERT_SKIP_HOLIDAYS] = updated.alerts.skipHolidays
            prefs[KEY_SYNC_INTERVAL] = updated.syncIntervalMinutes
                .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)
            prefs[KEY_RIPPLE_EFFECTS] = updated.rippleEffects
            prefs[KEY_THEME_REVEAL] = updated.themeReveal
            prefs[KEY_AUTO_CHECK_UPDATES] = updated.autoCheckUpdates
            prefs[KEY_INCLUDE_PRERELEASE] = updated.includePrerelease
            prefs[KEY_NOTIFY_UPDATES] = updated.notifyNewUpdates
        }
    }

    /**
     * Blocking reads for the OkHttp interceptors, which cannot suspend.
     *
     * They run on OkHttp's dispatcher threads, never on the main thread, and
     * DataStore serves everything after the first read from an in-memory cache,
     * so the cost is a thread hop rather than disk I/O.
     */
    fun tokenBlocking(): String? = runBlocking { currentSession()?.token }

    /**
     * The diary bearer, for `DiaryAuthInterceptor`.
     *
     * A second method rather than a parameter on [tokenBlocking], because the
     * two tokens are not two values of one thing: one of them can be present
     * while the other is absent, and a caller that took the wrong one would
     * send a class token to a family's diary.
     *
     * @see tokenBlocking
     */
    fun diaryTokenBlocking(): String? = runBlocking { currentDiarySession()?.token }

    /** @see tokenBlocking */
    fun baseUrlBlocking(): String = runBlocking { currentSettings().baseUrl }

    /**
     * The stored language, read the only way the caller can read it.
     *
     * `Activity.attachBaseContext` is where a per-app locale has to be applied
     * below API 33, and it cannot suspend and cannot wait for a flow: the base
     * context is already needed by the time the activity exists. The file is a
     * few hundred bytes and DataStore serves every read after the first from
     * memory, so this costs one disk read per process.
     */
    fun languageBlocking(): AppLanguage = runBlocking { currentSettings().language }

    /**
     * The shape of the cached schedule as of the previous sync.
     *
     * Kept here rather than in [AppSettings] because it is not a preference and
     * nothing outside the notification code has any business reading it: it
     * exists only so a sync can tell "the timetable changed" from "the timetable
     * was fetched again".
     */
    suspend fun scheduleFingerprint(): String? = preferences.first()[KEY_SCHEDULE_FINGERPRINT]

    /** @see scheduleFingerprint */
    suspend fun writeScheduleFingerprint(value: String) {
        dataStore.edit { prefs -> prefs[KEY_SCHEDULE_FINGERPRINT] = value }
    }

    /**
     * Points the app at one of the stored classes.
     *
     * The fingerprint goes with it, for the reason [clearSession] spells out:
     * it describes the shape of the schedule that was on screen a moment ago,
     * and comparing the next class's timetable against it would announce that
     * the schedule changed the instant somebody switched classes.
     */
    private fun MutablePreferences.activate(classId: Long) {
        this[MembershipKeys.ACTIVE_CLASS_ID] = classId
        remove(KEY_SCHEDULE_FINGERPRINT)
    }

    /** `null` unless a diary sign-in has actually stored a token. */
    private fun Preferences.toDiarySession(): DiarySession? {
        val token = this[KEY_DIARY_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
        return DiarySession(login = this[KEY_DIARY_LOGIN].orEmpty(), token = token)
    }

    /**
     * Enums are stored by name rather than by ordinal, and unknown names fall
     * back to the default instead of throwing: reordering an enum must not be
     * able to silently change what a user already chose, and a value written by
     * a newer build must not crash an older one.
     */
    private fun Preferences.toSettings(): AppSettings = AppSettings(
        baseUrl = this[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
        themeMode = ThemeMode.fromName(this[KEY_THEME_MODE]),
        dynamicColor = this[KEY_DYNAMIC_COLOR] ?: true,
        pitchBlack = this[KEY_PITCH_BLACK] ?: false,
        hapticsEnabled = this[KEY_HAPTICS] ?: true,
        hapticStrength = HapticStrength.fromName(this[KEY_HAPTIC_STRENGTH]),
        appFont = AppFont.fromName(this[KEY_APP_FONT]),
        language = AppLanguage.fromName(this[KEY_LANGUAGE]),
        textScale = (this[KEY_TEXT_SCALE] ?: AppSettings.DEFAULT_TEXT_SCALE)
            .coerceIn(AppSettings.TEXT_SCALE_RANGE),
        animations = this[KEY_ANIMATIONS] ?: true,
        motionSpeed = (this[KEY_MOTION_SPEED] ?: AppSettings.DEFAULT_MOTION_SPEED)
            .coerceIn(AppSettings.MOTION_SPEED_RANGE),
        swipeTabs = this[KEY_SWIPE_TABS] ?: true,
        defaultTab = HomeTab.fromName(this[KEY_DEFAULT_TAB]),
        motionBlur = this[KEY_MOTION_BLUR] ?: false,
        motionBlurScale = (this[KEY_MOTION_BLUR_SCALE] ?: AppSettings.DEFAULT_MOTION_BLUR_SCALE)
            .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE),
        edgeBlur = this[KEY_EDGE_BLUR] ?: true,
        showTeacher = this[KEY_SHOW_TEACHER] ?: true,
        widgetShowProgress = this[KEY_WIDGET_SHOW_PROGRESS] ?: true,
        todayShowHero = this[KEY_TODAY_SHOW_HERO] ?: true,
        todayLayout = TodayLayout.fromName(this[KEY_TODAY_LAYOUT]),
        todayWholeDay = this[KEY_TODAY_WHOLE_DAY] ?: false,
        todayHomeworkPreview = AppSettings.nearestHomeworkPreview(
            this[KEY_TODAY_HOMEWORK_PREVIEW] ?: AppSettings.DEFAULT_HOMEWORK_PREVIEW,
        ),
        todayShowEvents = this[KEY_TODAY_SHOW_EVENTS] ?: true,
        weekStart = WeekStart.fromName(this[KEY_WEEK_START]),
        weekShowWeekends = this[KEY_WEEK_WEEKENDS] ?: true,
        weekShowLoad = this[KEY_WEEK_LOAD] ?: true,
        weekShowEvents = this[KEY_WEEK_EVENTS] ?: true,
        weekShowHomework = this[KEY_WEEK_HOMEWORK] ?: true,
        debugMode = this[KEY_DEBUG_MODE] ?: false,
        // False only for a genuinely fresh install. The key arrived with the
        // introduction, so on every phone that had the app before it there is
        // no value here — and a plain `?: false` therefore promised four
        // screens of introduction to everyone who had been using the app all
        // term, the first time they signed out.
        //
        // A stored class token is the sentinel because it cannot be there by
        // accident: it is only written after a successful join, which is the
        // screen the introduction ends on. Someone holding one has been past
        // it, whatever else they have or have not touched.
        //
        // Both keys, because the membership list replaced the flat token and
        // the phones this sentinel exists for are exactly the ones that predate
        // it: reading only `KEY_TOKEN` would promise four screens of
        // introduction to every long-standing user the first time their
        // memberships were rewritten as a list.
        onboardingDone = this[KEY_ONBOARDING_DONE]
            ?: (this[MembershipKeys.TOKEN] != null || this[MembershipKeys.SESSIONS] != null),
        alerts = AlertPreferences(
            lessonSoon = this[KEY_ALERT_LESSON] ?: false,
            lessonLeadMinutes = this[KEY_ALERT_LEAD] ?: AlertPreferences.DefaultLeadMinutes,
            morningSummary = this[KEY_ALERT_MORNING] ?: false,
            morningAtMinutes = this[KEY_ALERT_MORNING_AT] ?: AlertPreferences.DefaultMorningMinutes,
            homeworkReminder = this[KEY_ALERT_HOMEWORK] ?: false,
            homeworkAtMinutes = this[KEY_ALERT_HOMEWORK_AT] ?: AlertPreferences.DefaultHomeworkMinutes,
            scheduleChanges = this[KEY_ALERT_CHANGES] ?: false,
            lessonDetail = LessonAlertDetail.fromName(this[KEY_ALERT_LESSON_DETAIL]),
            // Anything unparseable is dropped rather than defaulted: a set that
            // half survived an older build should lose the bad entries, not the
            // days the user actually picked.
            morningWeekdays = this[KEY_ALERT_MORNING_DAYS]
                ?.mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } }
                ?.toSet()
                ?: AlertPreferences.AllWeekdays,
            quietHours = this[KEY_ALERT_QUIET] ?: false,
            quietFromMinutes = this[KEY_ALERT_QUIET_FROM] ?: AlertPreferences.DefaultQuietFromMinutes,
            quietToMinutes = this[KEY_ALERT_QUIET_TO] ?: AlertPreferences.DefaultQuietToMinutes,
            skipHolidays = this[KEY_ALERT_SKIP_HOLIDAYS] ?: true,
        ),
        syncIntervalMinutes = (this[KEY_SYNC_INTERVAL] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)
            .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES),
        rippleEffects = this[KEY_RIPPLE_EFFECTS] ?: true,
        themeReveal = this[KEY_THEME_REVEAL] ?: true,
        autoCheckUpdates = this[KEY_AUTO_CHECK_UPDATES] ?: true,
        includePrerelease = this[KEY_INCLUDE_PRERELEASE] ?: false,
        notifyNewUpdates = this[KEY_NOTIFY_UPDATES] ?: true,
    )

    private companion object {
        // The membership keys live in `MembershipKeys`, beside the code that reads them.

        val KEY_DIARY_TOKEN = stringPreferencesKey("diary_token")
        val KEY_DIARY_LOGIN = stringPreferencesKey("diary_login")

        val KEY_DEBUG_MODE = booleanPreferencesKey("settings_debug_mode")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("settings_onboarding_done")

        val KEY_ALERT_LESSON = booleanPreferencesKey("alert_lesson_soon")
        val KEY_ALERT_LEAD = intPreferencesKey("alert_lesson_lead_minutes")
        val KEY_ALERT_MORNING = booleanPreferencesKey("alert_morning")
        val KEY_ALERT_MORNING_AT = intPreferencesKey("alert_morning_at_minutes")
        val KEY_ALERT_HOMEWORK = booleanPreferencesKey("alert_homework")
        val KEY_ALERT_HOMEWORK_AT = intPreferencesKey("alert_homework_at_minutes")
        val KEY_ALERT_CHANGES = booleanPreferencesKey("alert_schedule_changes")
        val KEY_ALERT_LESSON_DETAIL = stringPreferencesKey("alert_lesson_detail")
        val KEY_ALERT_MORNING_DAYS = stringSetPreferencesKey("alert_morning_weekdays")
        val KEY_ALERT_QUIET = booleanPreferencesKey("alert_quiet_hours")
        val KEY_ALERT_QUIET_FROM = intPreferencesKey("alert_quiet_from_minutes")
        val KEY_ALERT_QUIET_TO = intPreferencesKey("alert_quiet_to_minutes")
        val KEY_ALERT_SKIP_HOLIDAYS = booleanPreferencesKey("alert_skip_holidays")
        val KEY_SCHEDULE_FINGERPRINT = stringPreferencesKey("alert_schedule_fingerprint")

        val KEY_BASE_URL = stringPreferencesKey("settings_base_url")
        val KEY_THEME_MODE = stringPreferencesKey("settings_theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("settings_dynamic_color")
        val KEY_PITCH_BLACK = booleanPreferencesKey("settings_pitch_black")
        val KEY_HAPTICS = booleanPreferencesKey("settings_haptics_enabled")
        val KEY_HAPTIC_STRENGTH = stringPreferencesKey("settings_haptic_strength")
        val KEY_APP_FONT = stringPreferencesKey("settings_app_font")
        val KEY_LANGUAGE = stringPreferencesKey("settings_language")
        val KEY_TEXT_SCALE = floatPreferencesKey("settings_text_scale")
        val KEY_ANIMATIONS = booleanPreferencesKey("settings_animations")
        val KEY_MOTION_SPEED = floatPreferencesKey("settings_motion_speed")
        val KEY_SWIPE_TABS = booleanPreferencesKey("settings_swipe_tabs")
        val KEY_DEFAULT_TAB = stringPreferencesKey("settings_default_tab")
        val KEY_MOTION_BLUR = booleanPreferencesKey("settings_motion_blur")
        val KEY_MOTION_BLUR_SCALE = floatPreferencesKey("settings_motion_blur_scale")
        val KEY_EDGE_BLUR = booleanPreferencesKey("settings_edge_blur")
        val KEY_SHOW_TEACHER = booleanPreferencesKey("settings_show_teacher")
        val KEY_WIDGET_SHOW_PROGRESS = booleanPreferencesKey("settings_widget_show_progress")
        val KEY_TODAY_SHOW_HERO = booleanPreferencesKey("settings_today_show_hero")
        val KEY_TODAY_LAYOUT = stringPreferencesKey("settings_today_layout")
        val KEY_TODAY_WHOLE_DAY = booleanPreferencesKey("settings_today_whole_day")
        val KEY_TODAY_HOMEWORK_PREVIEW = intPreferencesKey("settings_today_homework_preview")
        val KEY_TODAY_SHOW_EVENTS = booleanPreferencesKey("settings_today_show_events")
        val KEY_WEEK_START = stringPreferencesKey("settings_week_start")
        val KEY_WEEK_WEEKENDS = booleanPreferencesKey("settings_week_weekends")
        val KEY_WEEK_LOAD = booleanPreferencesKey("settings_week_load")
        val KEY_WEEK_EVENTS = booleanPreferencesKey("settings_week_events")
        val KEY_WEEK_HOMEWORK = booleanPreferencesKey("settings_week_homework")
        val KEY_SYNC_INTERVAL = intPreferencesKey("settings_sync_interval_minutes")
        val KEY_RIPPLE_EFFECTS = booleanPreferencesKey("settings_ripple_effects")
        val KEY_THEME_REVEAL = booleanPreferencesKey("settings_theme_reveal")
        val KEY_AUTO_CHECK_UPDATES = booleanPreferencesKey("settings_auto_check_updates")
        val KEY_INCLUDE_PRERELEASE = booleanPreferencesKey("settings_include_prerelease")
        val KEY_NOTIFY_UPDATES = booleanPreferencesKey("settings_notify_updates")
    }
}
