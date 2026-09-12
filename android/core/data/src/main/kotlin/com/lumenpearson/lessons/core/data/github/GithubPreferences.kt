package com.lumenpearson.lessons.core.data.github

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Its own file, not a corner of the main preferences. A token for somebody's
 * GitHub account has nothing to do with which class this phone is in: signing
 * out of the class must not sign them out of GitHub, and clearing the app's
 * settings must not hand the next user a token. Separate files make both
 * impossible by construction rather than by remembering to skip a key.
 */
private val Context.githubDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "github",
    // @see com.lumenpearson.lessons.core.data.datastore.lessonsDataStore - a
    // corrupt file would otherwise make signing in again impossible, which is
    // the one action that could have fixed it.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Typed access to the stored GitHub token and the profile it belongs to. */
internal class GithubPreferences(context: Context) {

    private val dataStore = context.applicationContext.githubDataStore

    /**
     * Unreadable degrades to "signed out", which the user can fix from the
     * settings row; a crash on the way to the settings row they could not.
     */
    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    val account: Flow<GithubAccount?> = preferences.map { it.toAccount() }.distinctUntilChanged()

    suspend fun token(): String? = preferences.first()[KEY_TOKEN]?.takeIf { it.isNotBlank() }

    /** All three in one edit, so a token is never on disk without its login. */
    suspend fun write(token: String, account: GithubAccount) {
        dataStore.edit { prefs ->
            prefs[KEY_TOKEN] = token
            prefs[KEY_LOGIN] = account.login
            if (account.avatarUrl != null) prefs[KEY_AVATAR] = account.avatarUrl else prefs.remove(KEY_AVATAR)
        }
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_TOKEN)
            prefs.remove(KEY_LOGIN)
            prefs.remove(KEY_AVATAR)
        }
    }

    /**
     * Both halves or nothing: a token without a login would draw an empty row,
     * and [write] never stores one without the other anyway.
     */
    private fun Preferences.toAccount(): GithubAccount? {
        if (this[KEY_TOKEN].isNullOrBlank()) return null
        val login = this[KEY_LOGIN]?.takeIf { it.isNotBlank() } ?: return null
        return GithubAccount(login = login, avatarUrl = this[KEY_AVATAR]?.takeIf { it.isNotBlank() })
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_LOGIN = stringPreferencesKey("login")
        val KEY_AVATAR = stringPreferencesKey("avatar")
    }
}
