package com.lumenpearson.lessons.core.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.io.IOException

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
            prefs[KEY_DYNAMIC_COLOR] = updated.dynamicColor
            prefs[KEY_PITCH_BLACK] = updated.pitchBlack
            prefs[KEY_SHOW_TEACHER] = updated.showTeacher
            prefs[KEY_WIDGET_SHOW_PROGRESS] = updated.widgetShowProgress
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

    private fun Preferences.toSession(): Session? {
        val token = this[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return null
        return Session(
            classId = this[KEY_CLASS_ID] ?: 0L,
            className = this[KEY_CLASS_NAME].orEmpty(),
            school = this[KEY_SCHOOL],
            token = token,
        )
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        baseUrl = this[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
        dynamicColor = this[KEY_DYNAMIC_COLOR] ?: true,
        pitchBlack = this[KEY_PITCH_BLACK] ?: false,
        showTeacher = this[KEY_SHOW_TEACHER] ?: true,
        widgetShowProgress = this[KEY_WIDGET_SHOW_PROGRESS] ?: true,
        syncIntervalMinutes = (this[KEY_SYNC_INTERVAL] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)
            .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES),
    )

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("session_token")
        val KEY_CLASS_ID = longPreferencesKey("session_class_id")
        val KEY_CLASS_NAME = stringPreferencesKey("session_class_name")
        val KEY_SCHOOL = stringPreferencesKey("session_school")

        val KEY_BASE_URL = stringPreferencesKey("settings_base_url")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("settings_dynamic_color")
        val KEY_PITCH_BLACK = booleanPreferencesKey("settings_pitch_black")
        val KEY_SHOW_TEACHER = booleanPreferencesKey("settings_show_teacher")
        val KEY_WIDGET_SHOW_PROGRESS = booleanPreferencesKey("settings_widget_show_progress")
        val KEY_SYNC_INTERVAL = intPreferencesKey("settings_sync_interval_minutes")
    }
}
