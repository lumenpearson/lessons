package com.lumenpearson.lessons.core.data.developer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Its own file, for the reason `GithubPreferences` has one: none of this is
 * about the class or the diary, signing out of either must not touch it, and
 * clearing it — «Скрыть режим разработчика» — must not touch them.
 */
private val Context.developerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "developer",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

internal class DeveloperPreferences(context: Context) : DeveloperStore {

    private val dataStore = context.applicationContext.developerDataStore

    /** Unreadable reads as never found: the mode is the one thing here nobody depends on. */
    override val stored: Flow<DeveloperStored> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { it.toStored() }
        .distinctUntilChanged()

    override suspend fun update(transform: (DeveloperStored) -> DeveloperStored) {
        dataStore.edit { prefs -> prefs.write(transform(prefs.toStored())) }
    }

    private fun Preferences.toStored(): DeveloperStored = DeveloperStored(
        revealed = this[KEY_REVEALED] == true,
        verdict = this[KEY_LOGIN]?.takeIf { it.isNotBlank() }?.let { login ->
            DeveloperVerdict(
                login = login,
                role = this[KEY_ROLE]?.let { name -> DeveloperRole.entries.firstOrNull { it.name == name } },
                checkedAtMillis = this[KEY_CHECKED_AT] ?: 0L,
            )
        },
        // By name, and an unknown name dropped: a tool a later build added and
        // an earlier one cannot draw is simply not on here.
        tools = this[KEY_TOOLS].orEmpty().mapNotNull { name ->
            DeveloperTool.entries.firstOrNull { it.name == name }
        }.toSet(),
    )

    private fun MutablePreferences.write(stored: DeveloperStored) {
        this[KEY_REVEALED] = stored.revealed
        this[KEY_TOOLS] = stored.tools.map { it.name }.toSet()
        val verdict = stored.verdict
        if (verdict == null) {
            remove(KEY_LOGIN)
            remove(KEY_ROLE)
            remove(KEY_CHECKED_AT)
        } else {
            this[KEY_LOGIN] = verdict.login
            this[KEY_CHECKED_AT] = verdict.checkedAtMillis
            if (verdict.role != null) this[KEY_ROLE] = verdict.role.name else remove(KEY_ROLE)
        }
    }

    private companion object {
        val KEY_REVEALED = booleanPreferencesKey("revealed")
        val KEY_LOGIN = stringPreferencesKey("verdict_login")
        val KEY_ROLE = stringPreferencesKey("verdict_role")
        val KEY_CHECKED_AT = longPreferencesKey("verdict_checked_at")
        val KEY_TOOLS = stringSetPreferencesKey("tools")
    }
}
