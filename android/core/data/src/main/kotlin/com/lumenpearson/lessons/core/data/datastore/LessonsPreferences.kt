package com.lumenpearson.lessons.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.ThemeMode
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
)

/**
 * Typed access to the key-value store behind [Session] and [AppSettings].
 *
 * Session and settings share a file on purpose: they are written from the same
 * screens, they are both tiny, and a single file means a single fsync and a
 * single flow to observe.
 */
internal class LessonsPreferences(context: Context) {

    private val dataStore = context.applicationContext.lessonsDataStore

    /**
     * A corrupt or unreadable file must not take the app down: it degrades to
     * "no session, default settings", which lands the user on the join screen -
     * recoverable, unlike a crash loop at startup.
     */
    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    val session: Flow<Session?> = preferences.map { it.toSession() }.distinctUntilChanged()

    val settings: Flow<AppSettings> = preferences.map { it.toSettings() }.distinctUntilChanged()

    suspend fun currentSession(): Session? = preferences.first().toSession()

    suspend fun currentSettings(): AppSettings = preferences.first().toSettings()

    suspend fun writeSession(value: Session) {
        dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = value.token
            prefs[KEY_CLASS_ID] = value.classId
            prefs[KEY_CLASS_NAME] = value.className
            if (value.school != null) prefs[KEY_SCHOOL] = value.school else prefs.remove(KEY_SCHOOL)
        }
    }

    /** Clears identity only; the server address stays so re-joining is one field. */
    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_CLASS_ID)
            prefs.remove(KEY_CLASS_NAME)
            prefs.remove(KEY_SCHOOL)
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
            prefs[KEY_SWIPE_TABS] = updated.swipeTabs
            prefs[KEY_DEFAULT_TAB] = updated.defaultTab.name
            prefs[KEY_MOTION_BLUR] = updated.motionBlur
            prefs[KEY_MOTION_BLUR_SCALE] = updated.motionBlurScale
                .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE)
            prefs[KEY_EDGE_BLUR] = updated.edgeBlur
            prefs[KEY_SHOW_TEACHER] = updated.showTeacher
            prefs[KEY_WIDGET_SHOW_PROGRESS] = updated.widgetShowProgress
            prefs[KEY_DEBUG_MODE] = updated.debugMode
            prefs[KEY_ONBOARDING_DONE] = updated.onboardingDone
            prefs[KEY_ALERT_LESSON] = updated.alerts.lessonSoon
            prefs[KEY_ALERT_LEAD] = updated.alerts.lessonLeadMinutes
            prefs[KEY_ALERT_MORNING] = updated.alerts.morningSummary
            prefs[KEY_ALERT_MORNING_AT] = updated.alerts.morningAtMinutes
            prefs[KEY_ALERT_HOMEWORK] = updated.alerts.homeworkReminder
            prefs[KEY_ALERT_HOMEWORK_AT] = updated.alerts.homeworkAtMinutes
            prefs[KEY_ALERT_CHANGES] = updated.alerts.scheduleChanges
            prefs[KEY_SYNC_INTERVAL] = updated.syncIntervalMinutes
                .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)
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

    /** @see tokenBlocking */
    fun baseUrlBlocking(): String = runBlocking { currentSettings().baseUrl }

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

    private fun Preferences.toSession(): Session? {
        val token = this[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
        return Session(
            classId = this[KEY_CLASS_ID] ?: 0L,
            className = this[KEY_CLASS_NAME].orEmpty(),
            school = this[KEY_SCHOOL],
            token = token,
        )
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
        swipeTabs = this[KEY_SWIPE_TABS] ?: true,
        defaultTab = HomeTab.fromName(this[KEY_DEFAULT_TAB]),
        motionBlur = this[KEY_MOTION_BLUR] ?: false,
        motionBlurScale = (this[KEY_MOTION_BLUR_SCALE] ?: AppSettings.DEFAULT_MOTION_BLUR_SCALE)
            .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE),
        edgeBlur = this[KEY_EDGE_BLUR] ?: true,
        showTeacher = this[KEY_SHOW_TEACHER] ?: true,
        widgetShowProgress = this[KEY_WIDGET_SHOW_PROGRESS] ?: true,
        debugMode = this[KEY_DEBUG_MODE] ?: false,
        onboardingDone = this[KEY_ONBOARDING_DONE] ?: false,
        alerts = AlertPreferences(
            lessonSoon = this[KEY_ALERT_LESSON] ?: false,
            lessonLeadMinutes = this[KEY_ALERT_LEAD] ?: AlertPreferences.DefaultLeadMinutes,
            morningSummary = this[KEY_ALERT_MORNING] ?: false,
            morningAtMinutes = this[KEY_ALERT_MORNING_AT] ?: AlertPreferences.DefaultMorningMinutes,
            homeworkReminder = this[KEY_ALERT_HOMEWORK] ?: false,
            homeworkAtMinutes = this[KEY_ALERT_HOMEWORK_AT] ?: AlertPreferences.DefaultHomeworkMinutes,
            scheduleChanges = this[KEY_ALERT_CHANGES] ?: false,
        ),
        syncIntervalMinutes = (this[KEY_SYNC_INTERVAL] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)
            .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES),
    )

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("session_token")
        val KEY_CLASS_ID = longPreferencesKey("session_class_id")
        val KEY_CLASS_NAME = stringPreferencesKey("session_class_name")
        val KEY_SCHOOL = stringPreferencesKey("session_school")

        val KEY_DEBUG_MODE = booleanPreferencesKey("settings_debug_mode")
        val KEY_ONBOARDING_DONE = booleanPreferencesKey("settings_onboarding_done")

        val KEY_ALERT_LESSON = booleanPreferencesKey("alert_lesson_soon")
        val KEY_ALERT_LEAD = intPreferencesKey("alert_lesson_lead_minutes")
        val KEY_ALERT_MORNING = booleanPreferencesKey("alert_morning")
        val KEY_ALERT_MORNING_AT = intPreferencesKey("alert_morning_at_minutes")
        val KEY_ALERT_HOMEWORK = booleanPreferencesKey("alert_homework")
        val KEY_ALERT_HOMEWORK_AT = intPreferencesKey("alert_homework_at_minutes")
        val KEY_ALERT_CHANGES = booleanPreferencesKey("alert_schedule_changes")
        val KEY_SCHEDULE_FINGERPRINT = stringPreferencesKey("alert_schedule_fingerprint")

        val KEY_BASE_URL = stringPreferencesKey("settings_base_url")
        val KEY_THEME_MODE = stringPreferencesKey("settings_theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("settings_dynamic_color")
        val KEY_PITCH_BLACK = booleanPreferencesKey("settings_pitch_black")
        val KEY_HAPTICS = booleanPreferencesKey("settings_haptics_enabled")
        val KEY_HAPTIC_STRENGTH = stringPreferencesKey("settings_haptic_strength")
        val KEY_SWIPE_TABS = booleanPreferencesKey("settings_swipe_tabs")
        val KEY_DEFAULT_TAB = stringPreferencesKey("settings_default_tab")
        val KEY_MOTION_BLUR = booleanPreferencesKey("settings_motion_blur")
        val KEY_MOTION_BLUR_SCALE = floatPreferencesKey("settings_motion_blur_scale")
        val KEY_EDGE_BLUR = booleanPreferencesKey("settings_edge_blur")
        val KEY_SHOW_TEACHER = booleanPreferencesKey("settings_show_teacher")
        val KEY_WIDGET_SHOW_PROGRESS = booleanPreferencesKey("settings_widget_show_progress")
        val KEY_SYNC_INTERVAL = intPreferencesKey("settings_sync_interval_minutes")
    }
}
