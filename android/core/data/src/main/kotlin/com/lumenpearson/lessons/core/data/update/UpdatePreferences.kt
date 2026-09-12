package com.lumenpearson.lessons.core.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own file rather than two more keys in the main one. What the update
 * check remembers is bookkeeping about this install, not a preference: it must
 * survive a sign-out (the phone did not change), and it must not ride along in
 * the settings flow, which every screen observes and which would redraw for a
 * timestamp nobody displays.
 */
private val Context.updatesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "updates",
    // @see com.lumenpearson.lessons.core.data.datastore.lessonsDataStore - an
    // `edit` on a corrupt file throws however forgiving the read side is.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** The two things the update check remembers between launches. */
internal class UpdatePreferences(context: Context) {

    private val dataStore = context.applicationContext.updatesDataStore

    /** Unreadable degrades to "never checked, nothing dismissed", both harmless. */
    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    val dismissedTag: Flow<String?> = preferences.map { it[KEY_DISMISSED_TAG] }.distinctUntilChanged()

    suspend fun dismissedTag(): String? = preferences.first()[KEY_DISMISSED_TAG]

    suspend fun writeDismissedTag(tag: String) {
        dataStore.edit { prefs -> prefs[KEY_DISMISSED_TAG] = tag }
    }

    suspend fun lastCheckMillis(): Long? = preferences.first()[KEY_LAST_CHECK_MILLIS]

    suspend fun writeLastCheckMillis(value: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_CHECK_MILLIS] = value }
    }

    private companion object {
        val KEY_DISMISSED_TAG = stringPreferencesKey("dismissed_tag")
        val KEY_LAST_CHECK_MILLIS = longPreferencesKey("last_check_millis")
    }
}
