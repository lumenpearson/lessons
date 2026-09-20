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
 * What the preferences remember about a stored copy of the guide.
 *
 * @property language which of the two documents this record is about. The
 *   manifest names one version for both files and a refresh fetches one of
 *   them, so a release with no language on it is a version number that belongs
 *   to nothing in particular — see [storedCopyIsCurrent] for what that cost.
 */
internal data class StoredRelease(
    val version: Int,
    val updated: String,
    val appVersion: String,
    val language: String,
)

/**
 * The three places a guide can come from, as five methods.
 *
 * An interface because the real one is a DataStore, an assets folder and a file
 * in `filesDir`, none of which exist in a plain JVM test — and the rules
 * [DocsRepositoryImpl] applies over them (which copy wins, when a fetch is
 * worth making) are the half worth holding still. The same reasoning as
 * `DiarySessionStore`: what the repository is allowed to do to storage is
 * written down in one short list.
 */
internal interface DocsStorage {

    /** The release the stored markdown for [language] belongs to, if any. */
    suspend fun storedRelease(language: String): StoredRelease?

    /** The stored markdown for [language], or `null` if this phone has none. */
    suspend fun storedGuide(language: String): String?

    /** The copy built into the APK. Present on every install. */
    fun bundledGuide(language: String): String?

    /** The manifest built into the APK, so a bundled guide can name its version. */
    fun bundledManifest(): String?

    /** @return whether the copy actually landed; a failure costs the next launch a fetch. */
    suspend fun write(language: String, markdown: String, release: StoredRelease): Boolean
}

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
internal class DocsStore(context: Context) : DocsStorage {

    private val appContext: Context = context.applicationContext
    private val dataStore = appContext.docsDataStore

    private val preferences: Flow<Preferences> = dataStore.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    /** Where fetched markdown lands. Created on the first successful write. */
    private val folder: File get() = File(appContext.filesDir, "docs")

    private fun cached(language: String): File = File(folder, "guide.$language.md")

    override suspend fun storedRelease(language: String): StoredRelease? = runCatching {
        val values = preferences.first()
        val version = values[versionKey(language)] ?: return@runCatching null
        StoredRelease(
            version = version,
            updated = values[updatedKey(language)].orEmpty(),
            appVersion = values[appVersionKey(language)].orEmpty(),
            language = language,
        )
    }.getOrNull()

    override suspend fun storedGuide(language: String): String? = runCatching {
        val file = cached(language)
        if (file.isFile) file.readText() else null
    }.onFailure { failure ->
        Log.w(TAG, "Could not read the stored guide for $language", failure)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun bundledGuide(language: String): String? = runCatching {
        appContext.assets.open("guide.$language.md").use { stream -> stream.readBytes().decodeToString() }
    }.onFailure { failure ->
        // Not fatal and not silent: it means the build stopped shipping the
        // file, which no test on a device would otherwise report.
        Log.w(TAG, "The APK carries no bundled guide for $language", failure)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun bundledManifest(): String? = runCatching {
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
    override suspend fun write(
        language: String,
        markdown: String,
        release: StoredRelease,
    ): Boolean =
        runCatching {
            folder.mkdirs()
            val target = cached(language)
            // Through a temporary file, because a process killed mid-write
            // would otherwise leave half a guide where a whole one was.
            val partial = File(folder, "guide.$language.md.part")
            partial.writeText(markdown)
            check(partial.renameTo(target)) { "could not replace ${target.name}" }
            dataStore.edit { values ->
                values[versionKey(language)] = release.version
                values[updatedKey(language)] = release.updated
                values[appVersionKey(language)] = release.appVersion
            }
            true
        }.onFailure { failure ->
            Log.w(TAG, "Could not store the fetched guide for $language", failure)
        }.getOrDefault(false)

    private companion object {
        const val TAG = "Lessons"

        /**
         * One record per language, because there is one markdown file per
         * language and the manifest versions them together.
         *
         * The keys used to be `version`, `updated` and `app_version` flat, and
         * nothing reads those now. An install that holds them therefore starts
         * as if it had never fetched: the bundled copy is shown, the first
         * refresh downloads, and the phone is back where it was — one fetch,
         * against a stale guide that could otherwise never be replaced.
         */
        fun versionKey(language: String) = intPreferencesKey("version.$language")

        fun updatedKey(language: String) = stringPreferencesKey("updated.$language")

        fun appVersionKey(language: String) = stringPreferencesKey("app_version.$language")
    }
}
