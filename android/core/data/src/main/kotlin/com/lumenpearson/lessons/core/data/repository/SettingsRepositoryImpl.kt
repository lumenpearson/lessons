package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.model.AppLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Where the settings are kept: the part of [LessonsPreferences] this repository
 * reads, so that a test can hand it a map instead of a DataStore file, which
 * this module's JVM tests cannot open.
 */
internal interface SettingsStore {
    val settings: Flow<AppSettings>

    fun languageBlocking(): AppLanguage

    suspend fun currentSettings(): AppSettings

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)
}

/**
 * A thin pass-through to [LessonsPreferences], plus two side effects.
 *
 * It exists anyway so that consumers depend on an interface in this package
 * rather than on DataStore, which keeps the storage choice replaceable and keeps
 * `Preferences` out of every ViewModel's imports.
 *
 * Both side effects belong here rather than in the settings screen because a
 * preference has one owner: a change from anywhere — the first-run flow, the
 * settings page, a future quick setting — comes through [update], and only this
 * method sees all of them. Each is called only when its own part of the settings
 * actually moved, so toggling the theme goes near neither.
 *
 * @param onAlertsChanged re-arms the notification alarm when the alert block
 *   moved.
 * @param onWidgetSettingsChanged asks the widget to redraw when
 *   [AppSettings.drawnByWidget] moved. The widget redraws on a sync that changed
 *   the data and on its own tick, and a settings change is neither: switched to
 *   Russian, the widget went on drawing English until something else woke it,
 *   which on a day off is hours (#188).
 */
internal class SettingsRepositoryImpl(
    private val preferences: SettingsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onAlertsChanged: () -> Unit = {},
    private val onWidgetSettingsChanged: () -> Unit = {},
) : SettingsRepository {

    override val settings: Flow<AppSettings> = preferences.settings

    override fun languageBlocking(): AppLanguage = preferences.languageBlocking()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val before = preferences.currentSettings()
        preferences.updateSettings(transform)
        val after = preferences.currentSettings()
        if (before.alerts != after.alerts) {
            // Off the caller's thread: this is usually a view model scope on the
            // main dispatcher, and rescheduling reads the cached timetable.
            withContext(ioDispatcher) { onAlertsChanged() }
        }
        if (before.drawnByWidget != after.drawnByWidget) onWidgetSettingsChanged()
    }
}
