package com.lumenpearson.lessons.core.data.docs

import android.content.Context
import android.util.Log
import com.lumenpearson.lessons.core.data.github.GithubApi
import com.lumenpearson.lessons.core.data.repository.DocsFailure
import com.lumenpearson.lessons.core.data.repository.DocsLibrary
import com.lumenpearson.lessons.core.data.repository.DocsRepository
import com.lumenpearson.lessons.core.data.repository.DocsState
import com.lumenpearson.lessons.core.model.DocsGuide
import com.lumenpearson.lessons.core.model.DocsOrigin
import com.lumenpearson.lessons.core.model.DocsRelease
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request

/**
 * The guide, from three places in order of preference: the repository, this
 * phone's files, the APK.
 *
 * The APK's copy is what makes the other two optional. A reader who installs
 * the app on a train has documentation; a reader whose fetch fails has the last
 * one that worked. Nothing here can end with an empty screen, which is why
 * every step is guarded and why a failure only ever sets
 * [DocsState.failure] — the library on screen is replaced by a better one or
 * left exactly as it was.
 */
internal class DocsRepositoryImpl(
    private val store: DocsStorage,
    /**
     * The body of a `GET`, or `null` for anything that is not a 200 with text.
     *
     * Injected rather than called directly so the rules above it — which copy
     * of the guide wins, and when a fetch is worth making — can be exercised
     * without a network and without a `Context`. See [githubDownloader] for the
     * one production implementation.
     */
    private val download: (String) -> String?,
) : DocsRepository {

    constructor(context: Context) : this(DocsStore(context), githubDownloader(context))

    private val mutableState = MutableStateFlow(DocsState())
    override val state: StateFlow<DocsState> = mutableState.asStateFlow()

    /**
     * One reader at a time. The screen calls [load] as it opens and [refresh]
     * immediately after, and a pull-to-refresh may arrive while either is in
     * flight; without this, two fetches could publish in the order they
     * finished rather than the order they started.
     */
    private val gate = Mutex()

    override suspend fun load(language: String) {
        val wanted = normalise(language)
        gate.withLock {
            val current = mutableState.value.library
            if (current != null && current.guide.language == wanted) return
            val library = withContext(Dispatchers.IO) { storedOrBundled(wanted) }
            if (library != null) {
                mutableState.value = mutableState.value.copy(library = library)
            }
        }
    }

    override suspend fun refresh(language: String) {
        val wanted = normalise(language)
        gate.withLock {
            mutableState.value = mutableState.value.copy(refreshing = true)
            val outcome = withContext(Dispatchers.IO) { fetch(wanted) }
            mutableState.value = when (outcome) {
                is Outcome.Failed -> mutableState.value.copy(
                    refreshing = false,
                    failure = outcome.failure,
                )
                // A refresh that found nothing new still clears the failure: the
                // copy on screen is the current one, which is the question the
                // banner is answering.
                is Outcome.Unchanged -> mutableState.value.copy(refreshing = false, failure = null)
                is Outcome.Fetched -> DocsState(library = outcome.library, refreshing = false)
            }
        }
    }

    /**
     * The best copy already on the phone: what was fetched before, or the
     * APK's, whichever names the higher version.
     *
     * Not simply "stored wins". A phone that fetched version 3 a year ago and
     * has just been given an APK carrying version 5 would otherwise be shown
     * the older guide — and shown it for as long as the network is down, which
     * is exactly the case this fallback exists for. The fetched copy is only
     * newer *until* an install overtakes it.
     */
    private suspend fun storedOrBundled(language: String): DocsLibrary? {
        val stored = releaseAbout(language, store.storedRelease(language))
        val storedMarkdown = store.storedGuide(language)
        val bundledVersion = store.bundledManifest()?.let(::readManifest)?.version ?: 0
        if (stored != null && storedMarkdown != null &&
            preferStoredCopy(stored.version, bundledVersion)
        ) {
            val guide = DocsMarkdown.parse(storedMarkdown, language)
            if (guide.pages.isNotEmpty()) {
                return DocsLibrary(
                    guide = guide,
                    release = DocsRelease(
                        version = stored.version,
                        updated = stored.updated,
                        appVersion = stored.appVersion,
                        origin = DocsOrigin.STORED,
                    ),
                )
            }
            // Stored but unreadable: fall through to the bundled copy rather
            // than showing a blank guide. The next refresh overwrites it.
            Log.w(TAG, "The stored guide for $language parsed to nothing")
        }
        return bundled(language)
    }

    private fun bundled(language: String): DocsLibrary? {
        val markdown = store.bundledGuide(language) ?: return null
        val guide = DocsMarkdown.parse(markdown, language)
        if (guide.pages.isEmpty()) return null
        val manifest = store.bundledManifest()?.let(::readManifest)
        return DocsLibrary(
            guide = guide,
            release = DocsRelease(
                version = manifest?.version ?: 0,
                updated = manifest?.updated.orEmpty(),
                appVersion = manifest?.appVersion.orEmpty(),
                origin = DocsOrigin.BUNDLED,
            ),
        )
    }

    /**
     * Asks the repository for the manifest, and for the markdown only when the
     * manifest says this phone's copy is behind.
     *
     * The comparison is against what is *stored*, not against what is on
     * screen: a phone showing the bundled copy has no stored release at all, so
     * the first refresh always fetches — which is right, because the bundled
     * copy is as old as the install.
     */
    private suspend fun fetch(language: String): Outcome {
        val manifestText = download(GithubApi.rawUrl("$FOLDER/$MANIFEST"))
            ?: return Outcome.Failed(DocsFailure.OFFLINE)
        val manifest = readManifest(manifestText)
            ?: return Outcome.Failed(DocsFailure.UNREADABLE)

        val hasMarkdown = store.storedGuide(language) != null
        if (storedCopyIsCurrent(
                language = language,
                stored = store.storedRelease(language),
                hasMarkdown = hasMarkdown,
                manifestVersion = manifest.version,
            )
        ) {
            return Outcome.Unchanged
        }

        val file = manifest.files[language] ?: "guide.$language.md"
        val markdown = download(GithubApi.rawUrl("$FOLDER/$file"))
            ?: return Outcome.Failed(DocsFailure.OFFLINE)
        val guide = DocsMarkdown.parse(markdown, language)
        // An answer that parses to nothing is an answer that is not a guide —
        // a 404 page from a moved file, a captive portal, a truncated body.
        // Storing it would replace a working guide with an empty screen.
        if (guide.pages.isEmpty()) return Outcome.Failed(DocsFailure.UNREADABLE)

        val release = StoredRelease(
            version = manifest.version,
            updated = manifest.updated,
            appVersion = manifest.appVersion,
            language = language,
        )
        // The write may fail — a full disk, a revoked directory — and this
        // reader still gets the page they asked for. What it costs is the next
        // launch, which will fetch again instead of reading it back.
        store.write(language, markdown, release)
        return Outcome.Fetched(
            DocsLibrary(
                guide = guide,
                release = DocsRelease(
                    version = release.version,
                    updated = release.updated,
                    appVersion = release.appVersion,
                    origin = DocsOrigin.NETWORK,
                ),
            ),
        )
    }

    private fun readManifest(text: String): DocsManifest? = runCatching {
        GithubApi.json.decodeFromString<DocsManifest>(text)
    }.onFailure { failure ->
        Log.w(TAG, "The documentation manifest did not parse", failure)
    }.getOrNull()

    /** `ru` or `en`; a phone in any other language reads the English guide. */
    private fun normalise(language: String): String =
        if (language.lowercase().startsWith("ru")) "ru" else "en"

    private sealed interface Outcome {
        data object Unchanged : Outcome
        data class Fetched(val library: DocsLibrary) : Outcome
        data class Failed(val failure: DocsFailure) : Outcome
    }

    private companion object {

        /** Where the guide lives in the repository, and in the APK's assets. */
        const val FOLDER = "docs/app"
        const val MANIFEST = "manifest.json"
    }
}

/**
 * The real downloader: a plain `GET` over the GitHub client.
 *
 * A file-level function rather than a method because the repository no longer
 * owns it — the secondary constructor hands it in — and because the user agent
 * is read once, here, rather than on every request.
 */
private fun githubDownloader(context: Context): (String) -> String? {
    val userAgent = GithubApi.userAgent(GithubApi.installedVersion(context))
    return { url ->
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build()
            GithubApi.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub answered ${response.code} for $url")
                    null
                } else {
                    response.body.string().takeIf { it.isNotBlank() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            // The ordinary case: aeroplane mode, a dead hotspot, a timeout. Not
            // an error anybody needs to see in the log at warning level every
            // time the documentation is opened on a train.
            Log.d(TAG, "Could not reach GitHub for $url: ${failure.message}")
            null
        } catch (failure: IllegalStateException) {
            // OkHttp throws this for a malformed URL built from a manifest field.
            Log.w(TAG, "Refusing a documentation address that is not a URL", failure)
            null
        }
    }
}

/** Every line this file logs goes under the app's one tag. */
private const val TAG = "Lessons"

/**
 * The stored release, when it is the one describing [language]'s markdown.
 *
 * The manifest versions the two guides together and a refresh fetches one of
 * them, so «what version this phone holds» is a question per language and used
 * to be asked without one. A reader who looked at the English guide once took
 * the new version number for the phone, and the Russian file — still the one
 * downloaded in September — was from then on compared against it, found to be
 * current, and never fetched again. Nothing on the screen says a guide is
 * frozen, and every pull to refresh confirmed it was not.
 */
internal fun releaseAbout(language: String, stored: StoredRelease?): StoredRelease? =
    stored?.takeIf { it.language == language }

/**
 * Whether this phone already holds [language] at [manifestVersion].
 *
 * @param hasMarkdown whether there is a file beside the record at all. A
 *   release without its markdown is a version number for a guide that is not
 *   there, which the bundled copy would be shown over for ever.
 */
internal fun storedCopyIsCurrent(
    language: String,
    stored: StoredRelease?,
    hasMarkdown: Boolean,
    manifestVersion: Int,
): Boolean {
    val mine = releaseAbout(language, stored) ?: return false
    return hasMarkdown && mine.version >= manifestVersion
}

/**
 * Whether the copy fetched earlier is still the better of the two.
 *
 * A function of two numbers, pulled out of the read so that the one rule this
 * fallback has can be stated and checked: the rest of that path needs a
 * `Context`, assets and a DataStore, and none of those exist in a plain JVM
 * test.
 *
 * Equal versions keep the stored one. It is the same document, and reading the
 * file the phone already fetched costs nothing more than opening the asset
 * would.
 */
internal fun preferStoredCopy(storedVersion: Int, bundledVersion: Int): Boolean =
    storedVersion >= bundledVersion

/**
 * `docs/app/manifest.json`.
 *
 * Every field has a default because this is the one file that decides whether
 * a fetch happens at all: a manifest that grew a field would otherwise fail to
 * parse on every installed build, and the documentation would stop updating
 * everywhere at once.
 */
@Serializable
internal data class DocsManifest(
    val version: Int = 0,
    val updated: String = "",
    val appVersion: String = "",
    val files: Map<String, String> = emptyMap(),
)
