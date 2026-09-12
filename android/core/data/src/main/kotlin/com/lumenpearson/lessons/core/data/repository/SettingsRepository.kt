package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.model.AppLanguage
import kotlinx.coroutines.flow.Flow

/**
 * User preferences, shared by the app, the widget and the sync worker.
 *
 * Always emits a value - defaults stand in for anything never written - so no
 * consumer needs a null branch just to draw its first frame.
 */
interface SettingsRepository {

    val settings: Flow<AppSettings>

    /**
     * Read-modify-write inside DataStore's own transaction.
     *
     * Takes a lambda rather than individual setters so that two writers - the
     * settings screen and the widget's configuration activity - cannot clobber
     * each other's field.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings)

    /**
     * The stored language, without suspending.
     *
     * The one preference with a caller that cannot wait: below API 33 the
     * locale is applied by wrapping the activity's base context in
     * `attachBaseContext`, which runs before the activity exists and therefore
     * before anything can collect [settings]. Everything else reads the flow.
     */
    fun languageBlocking(): AppLanguage
}
