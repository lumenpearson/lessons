package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import kotlinx.coroutines.flow.Flow

/**
 * A thin pass-through to [LessonsPreferences].
 *
 * It exists anyway so that consumers depend on an interface in this package
 * rather than on DataStore, which keeps the storage choice replaceable and keeps
 * `Preferences` out of every ViewModel's imports.
 */
internal class SettingsRepositoryImpl(
    private val preferences: LessonsPreferences,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = preferences.settings

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        preferences.updateSettings(transform)
    }
}
