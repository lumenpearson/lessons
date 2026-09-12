package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.model.AppLanguage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * A thin pass-through to [LessonsPreferences], plus one side effect.
 *
 * It exists anyway so that consumers depend on an interface in this package
 * rather than on DataStore, which keeps the storage choice replaceable and keeps
 * `Preferences` out of every ViewModel's imports.
 *
 * @param onAlertsChanged re-arms the notification alarm. It belongs here rather
 *   than in the settings screen because a preference has one owner: turning an
 *   alert on from anywhere — the first-run flow, the settings page, a future
 *   quick setting — has to arm the chain, and only this method sees all of them.
 *   It is called only when the alert block actually moved, so toggling the theme
 *   does not go near an `AlarmManager`.
 */
internal class SettingsRepositoryImpl(
    private val preferences: LessonsPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onAlertsChanged: () -> Unit = {},
) : SettingsRepository {

    override val settings: Flow<AppSettings> = preferences.settings

    override fun languageBlocking(): AppLanguage = preferences.languageBlocking()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val before = preferences.currentSettings().alerts
        preferences.updateSettings(transform)
        val after = preferences.currentSettings().alerts
        if (before != after) {
            // Off the caller's thread: this is usually a view model scope on the
            // main dispatcher, and rescheduling reads the cached timetable.
            withContext(ioDispatcher) { onAlertsChanged() }
        }
    }
}
