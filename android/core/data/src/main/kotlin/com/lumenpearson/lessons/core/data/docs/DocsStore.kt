package com.lumenpearson.lessons.core.data.docs

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * Its own file, for the reason `UpdatePreferences` has one: what version of the
 * documentation this phone holds is bookkeeping about the install rather than a
 * preference. It must survive a sign-out, and it must not ride along in the
 * settings flow that every screen observes.
 */
private val Context.docsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "docs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Everything kept on the phone about the guide: which release it is, and the
 * markdown itself.
 *
 * The markdown is in ordinary files rather than in the DataStore or in Room.
 * It is two documents of a few tens of kilobytes that are written whole and
 * read whole, which is what a file is; putting them in the preferences would
 * load both into memory on every read of any key in it, and putting them in
 * Room would be a table with one row and a migration to write the next time the
 * format moved.
 *
 * **Every read and every write is guarded.** The storage is a cache with a
 * bundled fallback behind it, so a full disk, a revoked permission or a
 * half-written file must cost the reader the newest copy, never the screen.
 */
internal class DocsStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val dataStore = appContext.docsDataStore

    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    /** Where fetched markdown lands. Created on the first successful write. */
    private val folder: File get() = File(appContext.filesDir, "docs")

    private fun cached(language: String): File = File(folder, "guide.$language.md")

    /** The release the stored markdown belongs to, or `null` if none is stored. */
    suspend fun storedRelease(): StoredRelease? = runCatching {
        val values = preferences.first()
        val version = values[KEY_VERSION] ?: return@runCatching null
        StoredRelease(
            version = version,
            updated = values[KEY_UPDATED].orEmpty(),
            appVersion = values[KEY_APP_VERSION].orEmpty(),
        )
    }.getOrNull()

    /** The stored markdown for [language], or `null` if this phone has none. */
    suspend fun storedGuide(language: String): String? = runCatching {
        val file = cached(language)
        if (file.isFile) file.readText() else null
    }.onFailure { failure ->
        Log.w(TAG, "Could not read the stored guide for $language", failure)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The copy built into the APK. Present on every install; see the module's assets. */
    fun bundledGuide(language: String): String? = runCatching {
        appContext.assets.open("guide.$language.md").use { stream -> stream.readBytes().decodeToString() }
    }.onFailure { failure ->
        // Not fatal and not silent: it means the build stopped shipping the
        // file, which no test on a device would otherwise report.
        Log.w(TAG, "The APK carries no bundled guide for $language", failure)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** The manifest built into the APK, so a bundled guide can name its version. */
    fun bundledManifest(): String? = runCatching {
        appContext.assets.open("manifest.json").use { stream -> stream.readBytes().decodeToString() }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Writes the markdown first and the release second.
     *
     * The order is the whole of the crash safety: a release recorded before its
     * markdown landed would claim version 7 while the file on disk was still
     * version 6, and nothing would ever fetch 7 again. The other way round, a
     * crash in between leaves a newer file under an older version number — one
     * pointless fetch, and no wrong claim.
     */
    suspend fun write(language: String, markdown: String, release: StoredRelease): Boolean =
        runCatching {
            folder.mkdirs()
            val target = cached(language)
            // Through a temporary file, because a process killed mid-write
            // would otherwise leave half a guide where a whole one was.
            val partial = File(folder, "guide.$language.md.part")
            partial.writeText(markdown)
            check(partial.renameTo(target)) { "could not replace ${target.name}" }
            dataStore.edit { values ->
                values[KEY_VERSION] = release.version
                values[KEY_UPDATED] = release.updated
                values[KEY_APP_VERSION] = release.appVersion
            }
            true
        }.onFailure { failure ->
            Log.w(TAG, "Could not store the fetched guide for $language", failure)
        }.getOrDefault(false)

    /** What the preferences remember about the stored copy. */
    data class StoredRelease(
        val version: Int,
        val updated: String,
        val appVersion: String,
    )

    private companion object {
        const val TAG = "Lessons"
        val KEY_VERSION = intPreferencesKey("version")
        val KEY_UPDATED = stringPreferencesKey("updated")
        val KEY_APP_VERSION = stringPreferencesKey("app_version")
    }
}
